package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}

// fakeGateway 实现最小网关：auth → 处理一个请求。
// mode: "ping"（回 pong）/ "chunks"（先推两个二进制块再回响应）/ "install"（接收上传 + eod）
func fakeGateway(t *testing.T, mode string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer c.Close()

		_, msg, err := c.ReadMessage() // auth
		if err != nil {
			return
		}
		var a map[string]any
		_ = json.Unmarshal(msg, &a)
		if a["password"] != "pw" {
			_ = c.WriteMessage(websocket.TextMessage, []byte(`{"op":"auth","ok":false,"error":"bad password"}`))
			return
		}
		_ = c.WriteMessage(websocket.TextMessage, []byte(`{"op":"auth","ok":true}`))

		_, msg, err = c.ReadMessage() // request
		if err != nil {
			return
		}
		var req struct {
			ID     uint32          `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		_ = json.Unmarshal(msg, &req)

		idb := make([]byte, 4)
		binary.BigEndian.PutUint32(idb, req.ID)
		reply := func(result map[string]any) {
			b, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": req.ID, "result": result})
			_ = c.WriteMessage(websocket.TextMessage, b)
		}

		switch mode {
		case "chunks":
			_ = c.WriteMessage(websocket.BinaryMessage, append(append([]byte{}, idb...), []byte("hello ")...))
			_ = c.WriteMessage(websocket.BinaryMessage, append(append([]byte{}, idb...), []byte("world")...))
			reply(map[string]any{"exitCode": 0})
		case "install":
			var params struct {
				Size int `json:"size"`
			}
			_ = json.Unmarshal(req.Params, &params)
			got := make([]byte, 0, params.Size)
			for len(got) < params.Size {
				_, m, err := c.ReadMessage()
				if err != nil {
					return
				}
				if len(m) < 4 {
					continue
				}
				got = append(got, m[4:]...)
			}
			_, msg, err = c.ReadMessage() // eod
			if err != nil {
				return
			}
			var e struct {
				OP string `json:"op"`
				ID uint32 `json:"id"`
			}
			_ = json.Unmarshal(msg, &e)
			if e.OP != "eod" || e.ID != req.ID {
				reply(map[string]any{"exitCode": 9, "output": "no eod"})
				return
			}
			reply(map[string]any{"exitCode": 0, "output": fmt.Sprintf("Success %d", len(got))})
		default:
			reply(map[string]any{"pong": true})
		}
	}))
}

func dialTest(t *testing.T, gw *httptest.Server) *RPCClient {
	t.Helper()
	wsHost := "ws" + strings.TrimPrefix(gw.URL, "http")
	conn, _, err := websocket.DefaultDialer.Dial(wsHost, nil)
	if err != nil {
		t.Fatal(err)
	}
	return &RPCClient{conn: conn}
}

func TestCallWithChunks(t *testing.T) {
	gw := fakeGateway(t, "chunks")
	defer gw.Close()
	client := dialTest(t, gw)
	defer client.Close()

	var got bytes.Buffer
	res, err := client.Call("shell", map[string]any{"command": "echo hi"},
		func(b []byte) error {
			_, err := got.Write(b)
			return err
		}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got.String() != "hello world" {
		t.Fatalf("chunks = %q", got.String())
	}
	var r struct {
		ExitCode int `json:"exitCode"`
	}
	if err := json.Unmarshal(res, &r); err != nil {
		t.Fatal(err)
	}
	if r.ExitCode != 0 {
		t.Fatalf("exitCode = %d", r.ExitCode)
	}
}

func TestCallWithUpload(t *testing.T) {
	gw := fakeGateway(t, "install")
	defer gw.Close()
	client := dialTest(t, gw)
	defer client.Close()

	data := bytes.Repeat([]byte{0xAB}, 200*1024) // > 64KB，触发多块
	res, err := client.Call("pm.install", map[string]any{"size": len(data)}, nil, data)
	if err != nil {
		t.Fatal(err)
	}
	var r struct {
		ExitCode int    `json:"exitCode"`
		Output   string `json:"output"`
	}
	if err := json.Unmarshal(res, &r); err != nil {
		t.Fatal(err)
	}
	if r.ExitCode != 0 || r.Output != fmt.Sprintf("Success %d", len(data)) {
		t.Fatalf("install result: %+v", r)
	}
}

func TestDialGatewayViaMailbox(t *testing.T) {
	gw := fakeGateway(t, "ping")
	defer gw.Close()
	wsHost := "ws" + strings.TrimPrefix(gw.URL, "http")

	mb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/devices/dev1" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("content-type", "application/json")
		_, _ = w.Write([]byte(`{"domain": "` + wsHost + `"}`))
	}))
	defer mb.Close()

	tgt, err := ParsePairing("vibeadb://" + mb.URL + "/dev1#pw") // 带方案前缀，Resolve 原样使用
	if err != nil {
		t.Fatal(err)
	}
	client, err := DialGateway(tgt, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	res, err := client.Call("ping", map[string]any{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(res), "pong") {
		t.Fatalf("res = %s", res)
	}
}

func TestDialGatewayBadPassword(t *testing.T) {
	gw := fakeGateway(t, "ping")
	defer gw.Close()
	wsHost := "ws" + strings.TrimPrefix(gw.URL, "http")

	tgt, err := ParsePairing(wsHost + "#wrongpw")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := DialGateway(tgt, 0); err == nil {
		t.Fatal("expect auth failure")
	}
}
