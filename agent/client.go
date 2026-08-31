package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (e *rpcError) Error() string { return fmt.Sprintf("rpc %d: %s", e.Code, e.Message) }

type rpcResponse struct {
	ID     uint32          `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  *rpcError       `json:"error"`
}

type RPCClient struct {
	conn *websocket.Conn
	next uint32
	mu   sync.Mutex
}

// DialGateway 解析目标并建立已鉴权的连接；失败时有限重试
// （覆盖"多轮启动"：手机重开会话后信箱 URL 已更新，Agent 无需人工介入即可重连）。
func DialGateway(t *Target, retries int) (*RPCClient, error) {
	if retries < 0 {
		retries = 0
	}
	var lastErr error
	for i := 0; i <= retries; i++ {
		if i > 0 {
			time.Sleep(time.Duration(i) * 2 * time.Second)
		}
		host, err := t.Resolve()
		if err != nil {
			lastErr = err
			continue
		}
		conn, err := dialAndAuth(host, t.Password)
		if err != nil {
			lastErr = err
			continue
		}
		return &RPCClient{conn: conn}, nil
	}
	if lastErr == nil {
		lastErr = errors.New("unknown error")
	}
	return nil, fmt.Errorf("connect failed: %w", lastErr)
}

func dialAndAuth(host, password string) (*websocket.Conn, error) {
	url := host
	if !strings.Contains(url, "://") {
		url = "wss://" + url
	}
	d := websocket.Dialer{HandshakeTimeout: 20 * time.Second}
	conn, resp, err := d.Dial(url, nil)
	if err != nil {
		if resp != nil {
			return nil, fmt.Errorf("dial %s: HTTP %d", host, resp.StatusCode)
		}
		return nil, fmt.Errorf("dial %s: %w", host, err)
	}
	authReq, _ := json.Marshal(map[string]any{"op": "auth", "password": password})
	if err := conn.WriteMessage(websocket.TextMessage, authReq); err != nil {
		conn.Close()
		return nil, fmt.Errorf("auth write: %w", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(20 * time.Second))
	_, msg, err := conn.ReadMessage()
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("auth read: %w", err)
	}
	_ = conn.SetReadDeadline(time.Time{})
	var auth struct {
		OK    bool   `json:"ok"`
		Error string `json:"error"`
	}
	if err := json.Unmarshal(msg, &auth); err != nil || !auth.OK {
		conn.Close()
		if err == nil && auth.Error != "" {
			return nil, fmt.Errorf("auth failed: %s", auth.Error)
		}
		return nil, errors.New("auth failed")
	}
	return conn, nil
}

// Call 执行一次 JSON-RPC 请求并等待终结响应。
// onChunk 处理服务端推送的二进制块；upload 非 nil 时作为客户端上传数据（分块 + eod）。
func (c *RPCClient) Call(method string, params any, onChunk func([]byte) error, upload []byte) (json.RawMessage, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.next++
	id := c.next

	req, err := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params})
	if err != nil {
		return nil, err
	}
	if err := c.conn.WriteMessage(websocket.TextMessage, req); err != nil {
		return nil, err
	}
	if upload != nil {
		if err := sendChunks(c.conn, id, upload); err != nil {
			return nil, err
		}
		eod, _ := json.Marshal(map[string]any{"op": "eod", "id": id})
		if err := c.conn.WriteMessage(websocket.TextMessage, eod); err != nil {
			return nil, err
		}
	}
	for {
		mt, msg, err := c.conn.ReadMessage()
		if err != nil {
			return nil, err
		}
		switch mt {
		case websocket.TextMessage:
			var probe struct {
				OP string `json:"op"`
			}
			if err := json.Unmarshal(msg, &probe); err == nil && probe.OP != "" {
				continue // auth / eod 回执等控制帧
			}
			var r rpcResponse
			if err := json.Unmarshal(msg, &r); err != nil {
				continue
			}
			if r.ID != id {
				continue
			}
			if r.Error != nil {
				return nil, r.Error
			}
			return r.Result, nil
		case websocket.BinaryMessage:
			if len(msg) < 4 {
				continue
			}
			bid := binary.BigEndian.Uint32(msg[:4])
			if bid != id {
				continue
			}
			if onChunk != nil {
				if err := onChunk(msg[4:]); err != nil {
					return nil, err
				}
			}
		}
	}
}

func sendChunks(conn *websocket.Conn, id uint32, data []byte) error {
	const chunkSize = 64 * 1024
	prefix := make([]byte, 4)
	binary.BigEndian.PutUint32(prefix, id)
	for off := 0; off < len(data); off += chunkSize {
		end := off + chunkSize
		if end > len(data) {
			end = len(data)
		}
		frame := make([]byte, 0, 4+end-off)
		frame = append(frame, prefix...)
		frame = append(frame, data[off:end]...)
		if err := conn.WriteMessage(websocket.BinaryMessage, frame); err != nil {
			return err
		}
	}
	return nil
}

func (c *RPCClient) Close() error { return c.conn.Close() }
