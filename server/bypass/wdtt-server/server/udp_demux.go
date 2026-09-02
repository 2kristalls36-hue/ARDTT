package main

import (
	"net"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

type packetListener interface {
	Accept() (net.PacketConn, net.Addr, error)
	Close() error
	Addr() net.Addr
}

type timeoutError struct{}

func (timeoutError) Error() string   { return "i/o timeout" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }

type udpDemux struct {
	conn      *net.UDPConn
	mu        sync.Mutex
	sessions  map[string]*udpSession
	accept    chan *udpSession
	closed    chan struct{}
	closeOnce sync.Once
}

type udpSession struct {
	demux        *udpDemux
	remote       *net.UDPAddr
	packets      chan []byte
	done         chan struct{}
	closeOnce    sync.Once
	deadMu       sync.Mutex
	readDeadline time.Time
}

func listenUDPDemux(addr *net.UDPAddr) (*udpDemux, error) {
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return nil, err
	}
	setUDPBuffers(conn, socketBufSize)
	d := &udpDemux{
		conn:     conn,
		sessions: make(map[string]*udpSession),
		accept:   make(chan *udpSession, 64),
		closed:   make(chan struct{}),
	}
	go d.readLoop()
	return d, nil
}

func setUDPBuffers(conn *net.UDPConn, size int) {
	sc, err := conn.SyscallConn()
	if err != nil {
		return
	}
	_ = sc.Control(func(fd uintptr) {
		_ = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_RCVBUF, size)
		_ = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_SNDBUF, size)
	})
}

func (d *udpDemux) readLoop() {
	buf := make([]byte, 2048)
	for {
		n, addr, err := d.conn.ReadFromUDP(buf)
		if err != nil {
			select {
			case <-d.closed:
				return
			default:
				return
			}
		}
		key := addr.String()
		pkt := append([]byte(nil), buf[:n]...)

		d.mu.Lock()
		sess, ok := d.sessions[key]
		if !ok {
			select {
			case <-d.closed:
				d.mu.Unlock()
				return
			default:
			}
			sess = &udpSession{
				demux:   d,
				remote:  addr,
				packets: make(chan []byte, 256),
				done:    make(chan struct{}),
			}
			d.sessions[key] = sess
			d.mu.Unlock()
			select {
			case sess.packets <- pkt:
			default:
			}
			select {
			case d.accept <- sess:
			case <-d.closed:
				return
			}
			continue
		}
		d.mu.Unlock()

		select {
		case <-sess.done:
		case sess.packets <- pkt:
		default:
		}
	}
}

func (d *udpDemux) Accept() (net.PacketConn, net.Addr, error) {
	select {
	case <-d.closed:
		return nil, nil, net.ErrClosed
	case sess := <-d.accept:
		return sess, sess.remote, nil
	}
}

func (d *udpDemux) Close() error {
	d.closeOnce.Do(func() {
		close(d.closed)
		_ = d.conn.Close()
		d.mu.Lock()
		sessions := d.sessions
		d.sessions = make(map[string]*udpSession)
		d.mu.Unlock()
		for _, s := range sessions {
			s.closeOnce.Do(func() { close(s.done) })
		}
	})
	return nil
}

func (d *udpDemux) Addr() net.Addr {
	return d.conn.LocalAddr()
}

func (s *udpSession) Close() error {
	s.closeOnce.Do(func() {
		s.demux.mu.Lock()
		delete(s.demux.sessions, s.remote.String())
		s.demux.mu.Unlock()
		close(s.done)
	})
	return nil
}

func (s *udpSession) ReadFrom(p []byte) (int, net.Addr, error) {
	s.deadMu.Lock()
	deadline := s.readDeadline
	s.deadMu.Unlock()

	if !deadline.IsZero() {
		delay := time.Until(deadline)
		if delay <= 0 {
			return 0, nil, timeoutError{}
		}
		timer := time.NewTimer(delay)
		defer timer.Stop()
		select {
		case <-s.done:
			return 0, nil, net.ErrClosed
		case pkt := <-s.packets:
			return copy(p, pkt), s.remote, nil
		case <-timer.C:
			return 0, nil, timeoutError{}
		}
	}
	select {
	case <-s.done:
		return 0, nil, net.ErrClosed
	case pkt := <-s.packets:
		return copy(p, pkt), s.remote, nil
	}
}

func (s *udpSession) WriteTo(p []byte, _ net.Addr) (int, error) {
	return s.demux.conn.WriteToUDP(p, s.remote)
}

func (s *udpSession) LocalAddr() net.Addr { return s.demux.conn.LocalAddr() }
func (s *udpSession) SetDeadline(t time.Time) error {
	return s.SetReadDeadline(t)
}
func (s *udpSession) SetReadDeadline(t time.Time) error {
	s.deadMu.Lock()
	s.readDeadline = t
	s.deadMu.Unlock()
	return nil
}
func (s *udpSession) SetWriteDeadline(time.Time) error { return nil }
