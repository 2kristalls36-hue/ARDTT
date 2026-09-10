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

type deadlineSetter interface {
	SetDeadline(time.Time) error
}

type SessionControl struct {
	gen            atomic.Uint64
	userDataPaused atomic.Int32
	netOpsAllowed  atomic.Int32
	tunGen         atomic.Uint64
	netHandle      atomic.Int64
	netEpoch       atomic.Uint64
	socketsEpoch   atomic.Uint64
	stage          atomic.Value // string

	opMu          sync.Mutex
	tracked       map[uint64]deadlineSetter
	trackSeq      uint64
	socketsCtx    context.Context
	socketsCancel context.CancelFunc
	opsCtx        context.Context
	opsCancel     context.CancelFunc
}

func NewSessionControl() *SessionControl {
	c := &SessionControl{tracked: make(map[uint64]deadlineSetter)}
	c.gen.Store(1)
	c.netOpsAllowed.Store(1)
	c.stage.Store("starting")
	ctx, cancel := context.WithCancel(context.Background())
	c.socketsCtx = ctx
	c.socketsCancel = cancel
	ops, opsCancel := context.WithCancel(context.Background())
	c.opsCtx = ops
	c.opsCancel = opsCancel
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
		c.opMu.Lock()
		if c.opsCtx == nil || c.opsCtx.Err() != nil {
			ops, cancel := context.WithCancel(context.Background())
			c.opsCtx = ops
			c.opsCancel = cancel
		}
		c.opMu.Unlock()
		return
	}
	c.netOpsAllowed.Store(0)
	c.opMu.Lock()
	if c.opsCancel != nil {
		c.opsCancel()
	}
	c.opMu.Unlock()
	c.quiesceTracked(time.Now())
}

func (c *SessionControl) NetOpsAllowed() bool { return c.netOpsAllowed.Load() != 0 }

func shouldSendOnWire(ctrl *SessionControl) bool {
	return ctrl == nil || ctrl.NetOpsAllowed()
}

func (c *SessionControl) TryNetOp(fn func()) bool {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	if !c.NetOpsAllowed() {
		return false
	}
	fn()
	return true
}

func (c *SessionControl) TrackConn(d deadlineSetter) func() {
	if c == nil || d == nil {
		return func() {}
	}
	c.opMu.Lock()
	c.trackSeq++
	id := c.trackSeq
	if c.tracked == nil {
		c.tracked = make(map[uint64]deadlineSetter)
	}
	c.tracked[id] = d
	c.opMu.Unlock()
	return func() {
		c.opMu.Lock()
		delete(c.tracked, id)
		c.opMu.Unlock()
	}
}

func (c *SessionControl) quiesceTracked(at time.Time) {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	for _, d := range c.tracked {
		_ = d.SetDeadline(at)
	}
}

func (c *SessionControl) SocketsContext() context.Context {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	if c.socketsCtx == nil {
		return context.Background()
	}
	return c.socketsCtx
}

func (c *SessionControl) rotateSocketsLocked() {
	if c.socketsCancel != nil {
		c.socketsCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.socketsCtx = ctx
	c.socketsCancel = cancel
}

// ApplyNetwork is idempotent for the same handle. A real change bumps
// socketsEpoch and cancels in-flight dials/keepalive of the old network.
func (c *SessionControl) ApplyNetwork(handle int64) (sockEpoch uint64, changed bool) {
	if c.netHandle.Load() == handle {
		return c.socketsEpoch.Load(), false
	}
	c.netHandle.Store(handle)
	c.netEpoch.Add(1)
	c.opMu.Lock()
	c.rotateSocketsLocked()
	c.opMu.Unlock()
	c.quiesceTracked(time.Now())
	return c.socketsEpoch.Add(1), true
}

func (c *SessionControl) NetworkHandle() int64 { return c.netHandle.Load() }

func (c *SessionControl) NetEpoch() uint64 { return c.netEpoch.Load() }

func (c *SessionControl) SocketsEpoch() uint64 { return c.socketsEpoch.Load() }

// BoundContext is cancelled when FORBID_NET_OPS lands or the selected
// network changes. It does not cancel the logical CallSession.
func (c *SessionControl) BoundContext(parent context.Context) (context.Context, context.CancelFunc) {
	if c == nil {
		return context.WithCancel(parent)
	}
	c.opMu.Lock()
	ops := c.opsCtx
	sock := c.socketsCtx
	allowed := c.NetOpsAllowed()
	c.opMu.Unlock()
	ctx, cancel := context.WithCancel(parent)
	if !allowed {
		cancel()
		return ctx, cancel
	}
	go func() {
		var opsDone <-chan struct{}
		var sockDone <-chan struct{}
		if ops != nil {
			opsDone = ops.Done()
		}
		if sock != nil {
			sockDone = sock.Done()
		}
		select {
		case <-opsDone:
			cancel()
		case <-sockDone:
			cancel()
		case <-ctx.Done():
		}
	}()
	return ctx, cancel
}

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
		if cmd.SessionGen != rt.ctrl.Generation() {
			_ = f.Close()
			return ControlReply{Stale: true, Command: cmd.Name}
		}
		if err := rt.disp.AttachTUN(f); err != nil {
			_ = f.Close()
			return controlAck(cmd, gen, "err", err.Error())
		}
		return controlAck(cmd, rt.ctrl.Generation(), "ok", fmt.Sprintf("tunGen=%d", rt.disp.TunGeneration()))
	case "FORBID_NET_OPS":
		rt.ctrl.SetNetOpsAllowed(false)
		return controlAck(cmd, gen, "ok", "")
	case "ALLOW_NET_OPS":
		rt.ctrl.SetNetOpsAllowed(true)
		return controlAck(cmd, gen, "ok", "")
	case "UPDATE_NETWORK":
		handle := int64(0)
		kind := ""
		if len(cmd.Args) > 0 {
			kind = cmd.Args[0]
		}
		if len(cmd.Args) > 1 {
			if parsed, err := strconv.ParseInt(cmd.Args[1], 10, 64); err == nil {
				handle = parsed
			}
		}
		sockEpoch, changed := rt.ctrl.ApplyNetwork(handle)
		bound := "unchanged"
		if changed {
			bound = "applied"
		}
		payload := fmt.Sprintf("kind=%s|handle=%d|netEpoch=%d|socketsEpoch=%d|bound=%s", kind, handle, rt.ctrl.NetEpoch(), sockEpoch, bound)
		return controlAck(cmd, gen, "ok", payload)
	case "SHUTDOWN":
		ack := controlAck(cmd, gen, "ok", "")
		rt.ctrl.BumpGeneration()
		rt.ctrl.SetNetOpsAllowed(false)
		if rt.ctrl.socketsCancel != nil {
			rt.ctrl.opMu.Lock()
			rt.ctrl.rotateSocketsLocked()
			rt.ctrl.opMu.Unlock()
		}
		if rt.cancel != nil {
			rt.cancel()
		}
		return ack
	case "GET_TELEMETRY":
		payload := "stage=" + rt.stage()
		if rt.disp != nil {
			payload += "|" + rt.disp.Telemetry()
		}
		payload += fmt.Sprintf("|sessionGen=%d|paused=%d|netOps=%d", gen, boolToInt(rt.ctrl.UserDataPaused()), boolToInt(rt.ctrl.NetOpsAllowed()))
		return controlAck(cmd, gen, "ok", payload)
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
