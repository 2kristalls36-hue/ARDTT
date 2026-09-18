package main

import (
	"sync"
	"time"
)

type IPRateLimiter struct {
	mu      sync.Mutex
	rate    float64
	burst   float64
	buckets map[string]*ipBucket
}

type ipBucket struct {
	tokens float64
	last   time.Time
}

func NewIPRateLimiter(rps, burst float64) *IPRateLimiter {
	if rps <= 0 {
		rps = 8
	}
	if burst <= 0 {
		burst = rps
	}
	return &IPRateLimiter{rate: rps, burst: burst, buckets: map[string]*ipBucket{}}
}

func (l *IPRateLimiter) Allow(ip string) bool {
	if l == nil {
		return true
	}
	if ip == "" {
		ip = "unknown"
	}
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[ip]
	if b == nil {
		b = &ipBucket{tokens: l.burst, last: now}
		l.buckets[ip] = b
	}
	elapsed := now.Sub(b.last).Seconds()
	if elapsed > 0 {
		b.tokens += elapsed * l.rate
		if b.tokens > l.burst {
			b.tokens = l.burst
		}
		b.last = now
	}
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}
