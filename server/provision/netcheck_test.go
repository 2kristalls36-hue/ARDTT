package main

import "testing"

func TestClassifyNetflix(t *testing.T) {
	st, d := classifyNetflix(200, 200)
	if st != "ok" || d != "доступен" {
		t.Fatalf("full unlock: %s %s", st, d)
	}
	st, d = classifyNetflix(200, 404)
	if st != "restricted" {
		t.Fatalf("originals only: %s %s", st, d)
	}
	st, d = classifyNetflix(404, 404)
	if st != "blocked" {
		t.Fatalf("blocked: %s %s", st, d)
	}
	st, d = classifyNetflix(0, 0)
	if st != "error" {
		t.Fatalf("error: %s %s", st, d)
	}
}

func TestClassifyYouTube(t *testing.T) {
	st, _ := classifyYouTube(200, "<html>YouTube Premium</html>")
	if st != "ok" {
		t.Fatalf("ok: %s", st)
	}
	st, _ = classifyYouTube(200, "Premium is not available in your country")
	if st != "restricted" {
		t.Fatalf("geo: %s", st)
	}
	st, _ = classifyYouTube(0, "")
	if st != "error" {
		t.Fatalf("error: %s", st)
	}
}

func TestClassifyDisney(t *testing.T) {
	st, _ := classifyDisney(200, "https://www.disneyplus.com/home", "")
	if st != "ok" {
		t.Fatalf("ok: %s", st)
	}
	st, _ = classifyDisney(200, "https://preview.disneyplus.com/", "")
	if st != "restricted" {
		t.Fatalf("preview: %s", st)
	}
}

func TestClassifyChatGPT(t *testing.T) {
	st, d := classifyChatGPT(200, "fl=123\nloc=US\n")
	if st != "ok" || d != "доступен · US" {
		t.Fatalf("loc: %s %s", st, d)
	}
	st, _ = classifyChatGPT(200, "loc=T1\n")
	if st != "restricted" {
		t.Fatalf("T1: %s", st)
	}
	st, _ = classifyChatGPT(403, "")
	if st != "restricted" {
		t.Fatalf("403: %s", st)
	}
}

func TestClassifyIPType(t *testing.T) {
	st, d := classifyIPType(`{"status":"success","isp":"Cloudflare","hosting":true,"proxy":false}`, "")
	if st != "hosting" || d != "хостинг · Cloudflare" {
		t.Fatalf("hosting: %s %s", st, d)
	}
	st, d = classifyIPType(`{"status":"success","isp":"MTS","hosting":false,"proxy":false}`, "")
	if st != "isp" || d != "провайдер · MTS" {
		t.Fatalf("isp: %s %s", st, d)
	}
	st, _ = classifyIPType("", "timeout")
	if st != "error" {
		t.Fatalf("empty: %s", st)
	}
}

func TestParseCurlOutput(t *testing.T) {
	r := parseCurlOutput("hello\n__ARDTT_CODE__200\n__ARDTT_URL__https://example.com/final", "https://example.com")
	if r.Status != 200 || r.FinalURL != "https://example.com/final" || r.Body != "hello" {
		t.Fatalf("%+v", r)
	}
}

func TestClassifyGoogle(t *testing.T) {
	st, _ := classifyGoogle(204, "", "https://www.google.com/generate_204")
	if st != "ok" {
		t.Fatalf("ok: %s", st)
	}
	st, _ = classifyGoogle(200, "unusual traffic from your computer", "https://www.google.com/sorry/")
	if st != "restricted" {
		t.Fatalf("sorry: %s", st)
	}
}

func TestClassifyHttpService(t *testing.T) {
	st, d := classifyHttpService(200, "<html>ok</html>")
	if st != "ok" || d != "доступен" {
		t.Fatalf("ok: %s %s", st, d)
	}
	st, _ = classifyHttpService(200, "Access Denied by firewall", "access denied")
	if st != "restricted" {
		t.Fatalf("hint: %s", st)
	}
	st, _ = classifyHttpService(0, "")
	if st != "error" {
		t.Fatalf("error: %s", st)
	}
}
