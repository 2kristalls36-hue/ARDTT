package main

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

func TestFatalCallErrorIgnoresAnonymOutdated(t *testing.T) {
	resp := map[string]interface{}{
		"error": map[string]interface{}{
			"error_code": float64(100),
			"error_msg":  "anonym token outdated",
		},
	}
	if got := fatalCallError(resp); got != nil {
		t.Fatalf("error_code=100 must not be fatal: %v", got)
	}
}

func TestCallUnavailableErrorString(t *testing.T) {
	err := &CallUnavailableError{Code: 951}
	got := err.Error()
	if !strings.Contains(got, "VK call is unavailable") {
		t.Fatalf("expected Android-visible call text, got %q", got)
	}
	if !strings.Contains(got, "error_code=951") {
		t.Fatalf("expected error_code in message, got %q", got)
	}
}

func TestFatalCallErrorStillFlagsMissingCall(t *testing.T) {
	resp := map[string]interface{}{
		"error": map[string]interface{}{
			"error_code": float64(951),
			"error_msg":  "call not found",
		},
	}
	got := fatalCallError(resp)
	if got == nil || got.Code != 951 {
		t.Fatalf("expected fatal 951, got %#v", got)
	}
}

func TestStaleAnonymTokenFromOKCDN(t *testing.T) {
	err := &vkCallsOKAPIError{Code: 100, Message: "anonym token outdated"}
	wrapped := newVKCallsFailure("step5 vchat.joinConversationByLink", vkCallsFailureOKCDN, err)
	if !isStaleAnonymTokenError(wrapped) {
		t.Fatal("wrapped OKCDN anonym outdated must be retryable")
	}
	if !isStaleAnonymTokenError(err) {
		t.Fatal("bare OKCDN anonym outdated must be retryable")
	}
	if !isStaleAnonymTokenError(fmt.Errorf("error_code=100 anonym_token is outdated")) {
		t.Fatal("string form must match")
	}
	if isStaleAnonymTokenError(errors.New("error_code=14 captcha needed")) {
		t.Fatal("captcha must not look like stale anonym")
	}
	if isStaleAnonymTokenError(&CallUnavailableError{Code: 951, Message: "call not found"}) {
		t.Fatal("missing call is not a stale anonym token")
	}
}
