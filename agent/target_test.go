package main

import (
	"testing"
)

func TestParsePairingWorker(t *testing.T) {
	tg, err := ParsePairing("vibeadb://box.example.workers.dev/abcd1234ef567890#pw123")
	if err != nil {
		t.Fatal(err)
	}
	if !tg.viaMailbox() {
		t.Fatal("expect via mailbox")
	}
	if tg.MailboxHost != "box.example.workers.dev" {
		t.Fatalf("MailboxHost = %q", tg.MailboxHost)
	}
	if tg.DeviceID != "abcd1234ef567890" {
		t.Fatalf("DeviceID = %q", tg.DeviceID)
	}
	if tg.Password != "pw123" {
		t.Fatalf("Password = %q", tg.Password)
	}
	if tg.TunnelHost != "" {
		t.Fatalf("TunnelHost = %q, want empty", tg.TunnelHost)
	}
}

func TestParsePairingDirect(t *testing.T) {
	tg, err := ParsePairing("vibeadb://aaa-bbb.trycloudflare.com#pw")
	if err != nil {
		t.Fatal(err)
	}
	if tg.viaMailbox() {
		t.Fatal("expect direct")
	}
	if tg.TunnelHost != "aaa-bbb.trycloudflare.com" || tg.Password != "pw" {
		t.Fatalf("%+v", tg)
	}
}

func TestParsePairingErrors(t *testing.T) {
	bad := []string{
		"",
		"vibeadb://",
		"vibeadb://host#",
		"vibeadb://#pw",
		"vibeadb://host/#pw",
		"vibeadb:///dev#pw",
		"random-string",
	}
	for _, s := range bad {
		if _, err := ParsePairing(s); err == nil {
			t.Fatalf("expect error for %q", s)
		}
	}
}

func TestParsePairingWithSchemeHost(t *testing.T) {
	// 测试场景：信箱 host 可带 http:// 前缀（本地/测试环境）
	tg, err := ParsePairing("vibeadb://http://127.0.0.1:8787/dev1#pw")
	if err != nil {
		t.Fatal(err)
	}
	if tg.MailboxHost != "http://127.0.0.1:8787" || tg.DeviceID != "dev1" {
		t.Fatalf("%+v", tg)
	}
}

func TestResolveDirect(t *testing.T) {
	tg := &Target{TunnelHost: "x.trycloudflare.com", Password: "p"}
	host, err := tg.Resolve()
	if err != nil {
		t.Fatal(err)
	}
	if host != "x.trycloudflare.com" {
		t.Fatalf("host = %q", host)
	}
}
