package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

type execResult struct {
	ExitCode int    `json:"exitCode"`
	Stdout   string `json:"stdout"`
	Stderr   string `json:"stderr"`
	Output   string `json:"output"`
}

func printExecResult(res json.RawMessage) error {
	var r execResult
	_ = json.Unmarshal(res, &r)
	if r.Stdout != "" {
		fmt.Print(r.Stdout)
	}
	if r.Stderr != "" {
		fmt.Fprint(os.Stderr, r.Stderr)
	}
	if r.Output != "" && r.Stdout == "" {
		fmt.Print(r.Output)
	}
	if r.ExitCode != 0 {
		return fmt.Errorf("exit code %d", r.ExitCode)
	}
	return nil
}

func dialFor(fs *flag.FlagSet) (*RPCClient, error) {
	t, retries, err := mustTarget(fs)
	if err != nil {
		return nil, err
	}
	return DialGateway(t, retries)
}

func waitInterrupt() {
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
	signal.Stop(sig)
}

func cmdConnect(args []string) error {
	fs := newFlagSet("connect")
	if err := fs.Parse(args); err != nil {
		return err
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("ping", map[string]any{}, nil, nil)
	if err != nil {
		return err
	}
	fmt.Println("已连接:", string(res))
	waitInterrupt()
	fmt.Println("断开")
	return nil
}

func cmdPing(args []string) error {
	fs := newFlagSet("ping")
	if err := fs.Parse(args); err != nil {
		return err
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("ping", map[string]any{}, nil, nil)
	if err != nil {
		return err
	}
	fmt.Println("pong:", string(res))
	return nil
}

func cmdShell(args []string) error {
	fs := newFlagSet("shell")
	stream := fs.Bool("stream", false, "流式输出")
	timeout := fs.Int("timeout", 120, "非流式超时(秒)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	command := strings.Join(fs.Args(), " ")
	if command == "" {
		return errors.New("用法: vibeadb shell <command>")
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	if *stream {
		res, err := c.Call("shell", map[string]any{"command": command, "stream": true},
			func(b []byte) error {
				_, werr := os.Stdout.Write(b)
				return werr
			}, nil)
		if err != nil {
			return err
		}
		return printExecResult(res)
	}
	_ = c.conn.SetReadDeadline(time.Now().Add(time.Duration(*timeout+60) * time.Second))
	res, err := c.Call("shell", map[string]any{"command": command, "timeoutSec": *timeout}, nil, nil)
	_ = c.conn.SetReadDeadline(time.Time{})
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdScreenshot(args []string) error {
	fs := newFlagSet("screenshot")
	out := fs.String("o", "", "输出文件路径（默认 screenshot-<时间戳>.png）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	var buf bytes.Buffer
	if _, err := c.Call("screencap", map[string]any{}, func(b []byte) error {
		_, werr := buf.Write(b)
		return werr
	}, nil); err != nil {
		return err
	}
	path := *out
	if path == "" {
		path = fmt.Sprintf("screenshot-%d.png", time.Now().Unix())
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		return err
	}
	fmt.Printf("已保存 %s (%d bytes)\n", path, buf.Len())
	return nil
}

func cmdUIDump(args []string) error {
	fs := newFlagSet("ui")
	out := fs.String("o", "", "输出文件路径（默认打印到 stdout）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("ui.dump", map[string]any{}, nil, nil)
	if err != nil {
		return err
	}
	var r struct {
		XML string `json:"xml"`
	}
	if err := json.Unmarshal(res, &r); err != nil {
		return err
	}
	if *out != "" {
		if err := os.WriteFile(*out, []byte(r.XML), 0o644); err != nil {
			return err
		}
		fmt.Println("已保存", *out)
		return nil
	}
	fmt.Println(r.XML)
	return nil
}

func cmdTap(args []string) error {
	fs := newFlagSet("tap")
	if err := fs.Parse(args); err != nil {
		return err
	}
	a := fs.Args()
	if len(a) != 2 {
		return errors.New("用法: vibeadb tap <x> <y>")
	}
	x, err1 := strconv.Atoi(a[0])
	y, err2 := strconv.Atoi(a[1])
	if err1 != nil || err2 != nil {
		return errors.New("坐标必须是整数")
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("input", map[string]any{"kind": "tap", "x": x, "y": y}, nil, nil)
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdSwipe(args []string) error {
	fs := newFlagSet("swipe")
	if err := fs.Parse(args); err != nil {
		return err
	}
	a := fs.Args()
	if len(a) != 4 && len(a) != 5 {
		return errors.New("用法: vibeadb swipe <x1> <y1> <x2> <y2> [ms]")
	}
	nums := make([]int, len(a))
	for i, s := range a {
		n, err := strconv.Atoi(s)
		if err != nil {
			return errors.New("参数必须是整数")
		}
		nums[i] = n
	}
	params := map[string]any{"kind": "swipe", "x": nums[0], "y": nums[1], "x2": nums[2], "y2": nums[3]}
	if len(nums) == 5 {
		params["durationMs"] = nums[4]
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("input", params, nil, nil)
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdText(args []string) error {
	fs := newFlagSet("text")
	if err := fs.Parse(args); err != nil {
		return err
	}
	s := strings.Join(fs.Args(), " ")
	if s == "" {
		return errors.New("用法: vibeadb text <string>")
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("input", map[string]any{"kind": "text", "text": s}, nil, nil)
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdKey(args []string) error {
	fs := newFlagSet("key")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("用法: vibeadb key <keycode>")
	}
	code, err := strconv.Atoi(fs.Arg(0))
	if err != nil {
		return errors.New("keycode 必须是整数")
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("input", map[string]any{"kind": "keyevent", "keyCode": code}, nil, nil)
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdInstall(args []string) error {
	fs := newFlagSet("install")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("用法: vibeadb install <apk路径>")
	}
	data, err := os.ReadFile(fs.Arg(0))
	if err != nil {
		return err
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("pm.install", map[string]any{"size": len(data)}, nil, data)
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdUninstall(args []string) error {
	fs := newFlagSet("uninstall")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("用法: vibeadb uninstall <package>")
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("pm.uninstall", map[string]any{"package": fs.Arg(0)}, nil, nil)
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdPackages(args []string) error {
	fs := newFlagSet("packages")
	if err := fs.Parse(args); err != nil {
		return err
	}
	c, err := dialFor(fs)
	if err != nil {
		return err
	}
	defer c.Close()
	res, err := c.Call("pm.list", map[string]any{}, nil, nil)
	if err != nil {
		return err
	}
	return printExecResult(res)
}

func cmdLogcat(args []string) error {
	fs := newFlagSet("logcat")
	if err := fs.Parse(args); err != nil {
		return err
	}
	t, retries, err := mustTarget(fs)
	if err != nil {
		return err
	}
	c, err := DialGateway(t, retries)
	if err != nil {
		return err
	}
	defer c.Close()
	var interrupted int32
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		atomic.StoreInt32(&interrupted, 1)
		_ = c.Close()
	}()
	_, err = c.Call("logcat", map[string]any{}, func(b []byte) error {
		_, werr := os.Stdout.Write(b)
		return werr
	}, nil)
	if atomic.LoadInt32(&interrupted) == 1 {
		fmt.Fprintln(os.Stderr, "\n已停止")
		return nil
	}
	return err
}
