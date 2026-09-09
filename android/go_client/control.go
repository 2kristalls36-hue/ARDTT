package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Versioned Android↔Go control plane. Do not reuse PAUSE/RESUME semantics.
const controlVersion = "V1"

type ControlCmd struct {
	Version    string
	ReqID      string
	SessionGen uint64
	Name       string
	Args       []string
}

type ControlReply struct {
	Stale   bool
	Line    string
	Command string
}

type SessionControl struct {
	gen            atomic.Uint64
	userDataPaused atomic.Int32
	netOpsAllowed  atomic.Int32
	tunGen         atomic.Uint64
	stage          atomic.Value // string
}

func NewSessionControl() *SessionControl {
	c := &SessionControl{}
	c.gen.Store(1)
	c.netOpsAllowed.Store(1)
	c.stage.Store("starting")
	return c
}

func (c *SessionControl) Generation() uint64 { return c.gen.Load() }

func (c *SessionControl) BumpGeneration() uint64 {
	return c.gen.Add(1)
}

func (c *SessionControl) SetUserDataPaused(v bool) {
	if v {
		c.userDataPaused.Store(1)
	} else {
		c.userDataPaused.Store(0)
	}
}

func (c *SessionControl) UserDataPaused() bool { return c.userDataPaused.Load() != 0 }

func (c *SessionControl) SetNetOpsAllowed(v bool) {
	if v {
		c.netOpsAllowed.Store(1)
	} else {
		c.netOpsAllowed.Store(0)
	}
}

func (c *SessionControl) NetOpsAllowed() bool { return c.netOpsAllowed.Load() != 0 }

func (c *SessionControl) WaitNetOps(ctx context.Context) error {
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if c == nil || c.NetOpsAllowed() {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func parseControlLine(line string) (*ControlCmd, error) {
	if !strings.HasPrefix(line, controlVersion+"|") {
		return nil, fmt.Errorf("not a control frame")
	}
	parts := strings.Split(line, "|")
	if len(parts) < 4 {
		return nil, fmt.Errorf("short control frame")
	}
	gen, err := strconv.ParseUint(parts[2], 10, 64)
	if err != nil {
		return nil, fmt.Errorf("bad generation")
	}
	cmd := &ControlCmd{
		Version:    parts[0],
		ReqID:      parts[1],
		SessionGen: gen,
		Name:       parts[3],
	}
	if len(parts) > 4 {
		cmd.Args = parts[4:]
	}
	return cmd, nil
}

func controlAck(req *ControlCmd, currentGen uint64, status, payload string) ControlReply {
	if req.SessionGen != currentGen {
		return ControlReply{Stale: true, Command: req.Name}
	}
	if payload == "" {
		return ControlReply{
			Line:    fmt.Sprintf("%s|%s|%d|ACK|%s|%s", controlVersion, req.ReqID, currentGen, req.Name, status),
			Command: req.Name,
		}
	}
	return ControlReply{
		Line:    fmt.Sprintf("%s|%s|%d|ACK|%s|%s|%s", controlVersion, req.ReqID, currentGen, req.Name, status, payload),
		Command: req.Name,
	}
}

var activeSessionCtrl atomic.Pointer[SessionControl]
var controlRT atomic.Pointer[controlRuntime]

type controlRuntime struct {
	ctrl     *SessionControl
	disp     *Dispatcher
	tunSock  string
	cancel   context.CancelFunc
	listenMu sync.Mutex
}

func (rt *controlRuntime) handle(ctx context.Context, cmd *ControlCmd) ControlReply {
	gen := rt.ctrl.Generation()
	if cmd.SessionGen != gen {
		return ControlReply{Stale: true, Command: cmd.Name}
	}
	switch cmd.Name {
	case "PAUSE_USER_DATA":
		rt.ctrl.SetUserDataPaused(true)
		if rt.disp != nil {
			rt.disp.SetUserDataPaused(true)
		}
		return controlAck(cmd, gen, "ok", "")
	case "RESUME_CHANNELS":
		rt.ctrl.SetUserDataPaused(false)
		if rt.disp != nil {
			rt.disp.SetUserDataPaused(false)
		}
		return controlAck(cmd, gen, "ok", "")
	case "DETACH_TUN":
		if rt.disp != nil {
			rt.disp.DetachTUN()
		}
		return controlAck(cmd, gen, "ok", "")
	case "ATTACH_TUN":
		if rt.disp == nil || rt.tunSock == "" {
			return controlAck(cmd, gen, "err", "no-tun-sock")
		}
		rt.listenMu.Lock()
		f, err := recvTunFD(rt.tunSock)
		rt.listenMu.Unlock()
		if err != nil {
			return controlAck(cmd, gen, "err", err.Error())
		}
		if err := rt.disp.AttachTUN(f); err != nil {
			_ = f.Close()
			return controlAck(cmd, gen, "err", err.Error())
		}
		return controlAck(cmd, gen, "ok", fmt.Sprintf("tunGen=%d", rt.disp.TunGeneration()))
	case "FORBID_NET_OPS":
		rt.ctrl.SetNetOpsAllowed(false)
		return controlAck(cmd, gen, "ok", "")
	case "ALLOW_NET_OPS":
		rt.ctrl.SetNetOpsAllowed(true)
		return controlAck(cmd, gen, "ok", "")
	case "UPDATE_NETWORK":
		return controlAck(cmd, gen, "ok", strings.Join(cmd.Args, ","))
	case "GET_TELEMETRY":
		payload := "stage=" + rt.stage()
		if rt.disp != nil {
			payload += "|" + rt.disp.Telemetry()
		}
		payload += fmt.Sprintf("|sessionGen=%d|paused=%d|netOps=%d", gen, boolToInt(rt.ctrl.UserDataPaused()), boolToInt(rt.ctrl.NetOpsAllowed()))
		return controlAck(cmd, gen, "ok", payload)
	case "SHUTDOWN":
		if rt.cancel != nil {
			rt.cancel()
		}
		return controlAck(cmd, gen, "ok", "")
	default:
		return controlAck(cmd, gen, "err", "unknown")
	}
}

func (rt *controlRuntime) stage() string {
	if v, ok := rt.ctrl.stage.Load().(string); ok {
		return v
	}
	return "unknown"
}

func boolToInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
