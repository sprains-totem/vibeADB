// vibeADB Agent CLI
//
// 协议见 protocol/PROTOCOL.md。所有命令通过 --pairing（或环境变量 VIBEADB_PAIRING）
// 指定配对串；经信箱解析当前隧道域名后建立 WSS 连接。
package main

import (
	"fmt"
	"os"
)

const usageText = `vibeADB agent

用法: vibeadb <command> [flags] [args]

命令:
  connect                          连接并保持会话（Ctrl+C 退出）
  ping                             连通性测试
  shell <cmd>                      执行 shell 命令（--stream 流式, --timeout 秒）
  screenshot                       截图（-o 输出路径）
  ui                               取 UI 层级（uiautomator dump, -o 输出路径）
  tap <x> <y>                      点击
  swipe <x1> <y1> <x2> <y2> [ms]   滑动
  text <str>                       输入文本
  key <keycode>                    按键事件
  install <apk>                    安装 APK
  uninstall <pkg>                  卸载应用
  packages                         列出第三方应用
  logcat                           流式日志（Ctrl+C 退出）

通用 flags（所有命令）:
  --pairing string   配对串 vibeadb://...；默认读环境变量 VIBEADB_PAIRING
  --retries int      resolve/连接失败重试次数 (默认 3)
`

func usage() {
	fmt.Fprint(os.Stderr, usageText)
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	cmd := os.Args[1]
	args := os.Args[2:]

	var err error
	switch cmd {
	case "connect":
		err = cmdConnect(args)
	case "ping":
		err = cmdPing(args)
	case "shell":
		err = cmdShell(args)
	case "screenshot":
		err = cmdScreenshot(args)
	case "ui":
		err = cmdUIDump(args)
	case "tap":
		err = cmdTap(args)
	case "swipe":
		err = cmdSwipe(args)
	case "text":
		err = cmdText(args)
	case "key":
		err = cmdKey(args)
	case "install":
		err = cmdInstall(args)
	case "uninstall":
		err = cmdUninstall(args)
	case "packages":
		err = cmdPackages(args)
	case "logcat":
		err = cmdLogcat(args)
	case "help", "-h", "--help":
		usage()
		return
	default:
		fmt.Fprintf(os.Stderr, "未知命令: %s\n\n", cmd)
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "错误:", err)
		os.Exit(1)
	}
}
