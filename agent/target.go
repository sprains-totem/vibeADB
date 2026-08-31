package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// Target 描述一次连接目标，来自配对串。
type Target struct {
	MailboxHost string // 非空 = 经信箱解析（可带 http:// 前缀用于测试）
	DeviceID    string
	TunnelHost  string // 直连（无信箱变体）
	Password    string
}

func (t *Target) viaMailbox() bool { return t.DeviceID != "" }

// ParsePairing 解析 vibeadb://<worker>/<deviceId>#<password> 或 vibeadb://<host>#<password>。
// 直连 host 允许带 ws:// / wss:// 等协议前缀（测试/本地环境用），解析后原样保留。
func ParsePairing(raw string) (*Target, error) {
	s := strings.TrimSpace(raw)
	s = strings.TrimPrefix(s, "vibeadb://")
	body, password, found := strings.Cut(s, "#")
	if !found || body == "" || password == "" {
		return nil, errors.New("无效配对串（应为 vibeadb://<worker>/<deviceId>#<password> 或 vibeadb://<host>#<password>）")
	}
	// 先剥掉可选的 scheme，再按最后一个 "/" 切分 worker / deviceId
	rest := body
	prefixLen := 0
	if i := strings.Index(rest, "://"); i >= 0 {
		prefixLen = i + 3
		rest = rest[prefixLen:]
	}
	if idx := strings.LastIndex(rest, "/"); idx >= 0 {
		worker := body[:prefixLen+idx]
		device := rest[idx+1:]
		if worker == "" || device == "" {
			return nil, errors.New("无效配对串：worker 或 deviceId 为空")
		}
		return &Target{MailboxHost: worker, DeviceID: device, Password: password}, nil
	}
	return &Target{TunnelHost: body, Password: password}, nil
}

// Resolve 返回当前隧道主机名（直连模式原样返回）。
func (t *Target) Resolve() (string, error) {
	if !t.viaMailbox() {
		return t.TunnelHost, nil
	}
	base := t.MailboxHost
	if !strings.Contains(base, "://") {
		base = "https://" + base
	}
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(base + "/devices/" + t.DeviceID)
	if err != nil {
		return "", fmt.Errorf("resolve: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return "", errors.New("resolve: 设备未上线（手机端会话未运行）")
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return "", fmt.Errorf("resolve: HTTP %d: %s", resp.StatusCode, string(b))
	}
	var out struct {
		Domain string `json:"domain"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", fmt.Errorf("resolve: %w", err)
	}
	if out.Domain == "" {
		return "", errors.New("resolve: 信箱返回空 domain")
	}
	return out.Domain, nil
}

func newFlagSet(name string) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	fs.String("pairing", os.Getenv("VIBEADB_PAIRING"), "配对串 vibeadb://...；默认读环境变量 VIBEADB_PAIRING")
	fs.Int("retries", 3, "resolve/连接失败重试次数")
	return fs
}

// mustTarget 从已 Parse 的 FlagSet 取配对串与重试次数。
func mustTarget(fs *flag.FlagSet) (*Target, int, error) {
	p := fs.Lookup("pairing").Value.String()
	if p == "" {
		return nil, 0, errors.New("缺少配对串：--pairing 或环境变量 VIBEADB_PAIRING")
	}
	t, err := ParsePairing(p)
	if err != nil {
		return nil, 0, err
	}
	retries, _ := strconv.Atoi(fs.Lookup("retries").Value.String())
	return t, retries, nil
}
