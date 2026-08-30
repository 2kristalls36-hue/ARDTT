package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// Lightweight service probes inspired by (not wrapping) the public VPS checkers:
//   https://github.com/xykt/IPQuality
//   https://github.com/lmc999/RegionRestrictionCheck
// Full bash scripts are not executed.

const (
	netcheckTTL      = 3 * time.Minute
	netcheckTimeout  = 8 * time.Second
	netcheckMaxBody  = 64 << 10
	netcheckBrowserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

type netcheckItem struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	Status string `json:"status"`
	Detail string `json:"detail"`
}

type netcheckReport struct {
	OK        bool           `json:"ok"`
	ViaWarp   bool           `json:"viaWarp"`
	Cached    bool           `json:"cached"`
	CheckedAt int64          `json:"checkedAt"`
	Items     []netcheckItem `json:"items"`
}

type fetchResult struct {
	Status   int
	Body     string
	FinalURL string
	Err      string
}

type httpFetcher func(rawURL string, viaWarp bool) fetchResult

var (
	netcheckMu    sync.Mutex
	netcheckStore = map[string]netcheckReport{}
	netcheckFetch httpFetcher = fetchHTTP
)

func getNetcheck(viaWarp, refresh bool) netcheckReport {
	key := "wan"
	if viaWarp {
		key = "warp"
	}
	now := time.Now()
	if !refresh {
		netcheckMu.Lock()
		if cached, ok := netcheckStore[key]; ok && now.Sub(time.Unix(cached.CheckedAt, 0)) < netcheckTTL {
			out := cached
			out.Cached = true
			netcheckMu.Unlock()
			return out
		}
		netcheckMu.Unlock()
	}
	report := runNetcheck(viaWarp)
	netcheckMu.Lock()
	netcheckStore[key] = report
	netcheckMu.Unlock()
	return report
}

func runNetcheck(viaWarp bool) netcheckReport {
	type slot struct {
		id    string
		label string
		run   func() (status, detail string)
	}
	slots := []slot{
		{"ip_type", "Тип IP", func() (string, string) {
			r := netcheckFetch("http://ip-api.com/json/?fields=status,isp,org,hosting,proxy,query", viaWarp)
			return classifyIPType(r.Body, r.Err)
		}},
		{"netflix", "Netflix", func() (string, string) {
			a := netcheckFetch("https://www.netflix.com/title/81280792", viaWarp)
			b := netcheckFetch("https://www.netflix.com/title/70143836", viaWarp)
			return classifyNetflix(a.Status, b.Status)
		}},
		{"youtube", "YouTube", func() (string, string) {
			r := netcheckFetch("https://www.youtube.com/premium", viaWarp)
			return classifyYouTube(r.Status, r.Body)
		}},
		{"disney", "Disney+", func() (string, string) {
			r := netcheckFetch("https://www.disneyplus.com", viaWarp)
			return classifyDisney(r.Status, r.FinalURL, r.Body)
		}},
		{"chatgpt", "ChatGPT", func() (string, string) {
			r := netcheckFetch("https://chat.openai.com/cdn-cgi/trace", viaWarp)
			return classifyChatGPT(r.Status, r.Body)
		}},
		{"google", "Google", func() (string, string) {
			r := netcheckFetch("https://www.google.com/generate_204", viaWarp)
			return classifyGoogle(r.Status, r.Body, r.FinalURL)
		}},
		{"tiktok", "TikTok", func() (string, string) {
			r := netcheckFetch("https://www.tiktok.com", viaWarp)
			return classifyHttpService(r.Status, r.Body, "access denied", "not available in your region")
		}},
		{"instagram", "Instagram", func() (string, string) {
			r := netcheckFetch("https://www.instagram.com", viaWarp)
			return classifyHttpService(r.Status, r.Body, "not available in your country")
		}},
		{"reddit", "Reddit", func() (string, string) {
			r := netcheckFetch("https://www.reddit.com", viaWarp)
			return classifyHttpService(r.Status, r.Body, "blocked", "not available")
		}},
	}

	items := make([]netcheckItem, len(slots))
	var wg sync.WaitGroup
	for i := range slots {
		i := i
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, detail := slots[i].run()
			items[i] = netcheckItem{
				ID:     slots[i].id,
				Label:  slots[i].label,
				Status: status,
				Detail: detail,
			}
		}()
	}
	wg.Wait()
	return netcheckReport{
		OK:        true,
		ViaWarp:   viaWarp,
		Cached:    false,
		CheckedAt: time.Now().Unix(),
		Items:     items,
	}
}

func classifyIPType(body, fetchErr string) (string, string) {
	if strings.TrimSpace(body) == "" {
		if fetchErr != "" {
			return "error", "не удалось определить"
		}
		return "error", "не удалось определить"
	}
	var parsed struct {
		Status  string `json:"status"`
		ISP     string `json:"isp"`
		Org     string `json:"org"`
		Hosting bool   `json:"hosting"`
		Proxy   bool   `json:"proxy"`
	}
	if err := json.Unmarshal([]byte(body), &parsed); err != nil || parsed.Status == "fail" {
		return "error", "не удалось определить"
	}
	name := strings.TrimSpace(parsed.ISP)
	if name == "" {
		name = strings.TrimSpace(parsed.Org)
	}
	switch {
	case parsed.Proxy:
		if name != "" {
			return "proxy", "прокси · " + name
		}
		return "proxy", "прокси"
	case parsed.Hosting:
		if name != "" {
			return "hosting", "хостинг · " + name
		}
		return "hosting", "хостинг"
	default:
		if name != "" {
			return "isp", "провайдер · " + name
		}
		return "isp", "провайдер"
	}
}

func classifyNetflix(selfCode, licensedCode int) (string, string) {
	selfOK := selfCode >= 200 && selfCode < 400 && selfCode != 404
	licOK := licensedCode >= 200 && licensedCode < 400 && licensedCode != 404
	switch {
	case selfOK && licOK:
		return "ok", "доступен"
	case selfOK:
		return "restricted", "только свои сериалы"
	case selfCode == 0 && licensedCode == 0:
		return "error", "не удалось проверить"
	default:
		return "blocked", "недоступен"
	}
}

func classifyYouTube(code int, body string) (string, string) {
	lower := strings.ToLower(body)
	if strings.Contains(lower, "premium is not available in your country") ||
		strings.Contains(lower, "www.google.com/sorry") {
		return "restricted", "ограничен"
	}
	if code >= 200 && code < 400 {
		return "ok", "доступен"
	}
	if code == 0 {
		return "error", "не удалось проверить"
	}
	return "blocked", "недоступен"
}

func classifyDisney(code int, finalURL, body string) (string, string) {
	u := strings.ToLower(finalURL)
	b := strings.ToLower(body)
	if strings.Contains(u, "preview") ||
		strings.Contains(u, "unavailable") ||
		strings.Contains(b, "not available in your region") ||
		strings.Contains(b, "disney+ is not available") {
		return "restricted", "ограничен"
	}
	if code >= 200 && code < 400 {
		return "ok", "доступен"
	}
	if code == 0 {
		return "error", "не удалось проверить"
	}
	return "blocked", "недоступен"
}

func classifyGoogle(code int, body, finalURL string) (string, string) {
	lower := strings.ToLower(body + " " + finalURL)
	if strings.Contains(lower, "www.google.com/sorry") ||
		strings.Contains(lower, "unusual traffic") {
		return "restricted", "ограничен"
	}
	if code == 204 || (code >= 200 && code < 400) {
		return "ok", "доступен"
	}
	if code == 0 {
		return "error", "не удалось проверить"
	}
	return "blocked", "недоступен"
}

func classifyHttpService(code int, body string, restrictedHints ...string) (string, string) {
	lower := strings.ToLower(body)
	for _, hint := range restrictedHints {
		if hint != "" && strings.Contains(lower, strings.ToLower(hint)) {
			return "restricted", "ограничен"
		}
	}
	if code >= 200 && code < 400 {
		return "ok", "доступен"
	}
	if code == 0 {
		return "error", "не удалось проверить"
	}
	return "blocked", "недоступен"
}

func classifyChatGPT(code int, body string) (string, string) {
	lower := strings.ToLower(body)
	if strings.Contains(lower, "unsupported_country") {
		return "restricted", "ограничен"
	}
	loc := ""
	for _, line := range strings.Split(body, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(strings.ToLower(line), "loc=") {
			loc = strings.TrimSpace(line[4:])
			break
		}
	}
	if loc != "" && !strings.EqualFold(loc, "T1") {
		return "ok", "доступен · " + loc
	}
	if loc == "T1" {
		return "restricted", "ограничен"
	}
	if code == 403 || code == 451 {
		return "restricted", "ограничен"
	}
	if code >= 200 && code < 400 {
		return "ok", "доступен"
	}
	if code == 0 {
		return "error", "не удалось проверить"
	}
	return "blocked", "недоступен"
}

func fetchHTTP(rawURL string, viaWarp bool) fetchResult {
	if viaWarp {
		return fetchCurl(rawURL, true)
	}
	return fetchGo(rawURL)
}

func fetchGo(rawURL string) fetchResult {
	client := &http.Client{
		Timeout: netcheckTimeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 8 {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
	req, err := http.NewRequest(http.MethodGet, rawURL, nil)
	if err != nil {
		return fetchResult{Err: err.Error()}
	}
	req.Header.Set("User-Agent", netcheckBrowserUA)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
	resp, err := client.Do(req)
	if err != nil {
		return fetchResult{Err: err.Error()}
	}
	defer resp.Body.Close()
	limited := io.LimitReader(resp.Body, netcheckMaxBody)
	raw, _ := io.ReadAll(limited)
	final := rawURL
	if resp.Request != nil && resp.Request.URL != nil {
		final = resp.Request.URL.String()
	}
	return fetchResult{
		Status:   resp.StatusCode,
		Body:     string(raw),
		FinalURL: final,
	}
}

func fetchCurl(rawURL string, viaWarp bool) fetchResult {
	args := []string{
		"-sS", "-L", "--max-redirs", "8",
		"--max-time", "8",
		"-A", netcheckBrowserUA,
		"-w", "\n__NVPN_CODE__%{http_code}\n__NVPN_URL__%{url_effective}",
	}
	if viaWarp {
		args = append(args, "--interface", "warp0")
	}
	args = append(args, rawURL)
	out, err := exec.Command("curl", args...).CombinedOutput()
	text := string(out)
	if err != nil && !strings.Contains(text, "__NVPN_CODE__") {
		return fetchResult{Err: strings.TrimSpace(text + " " + err.Error())}
	}
	return parseCurlOutput(text, rawURL)
}

func parseCurlOutput(text, fallbackURL string) fetchResult {
	code := 0
	final := fallbackURL
	body := text
	if i := strings.LastIndex(text, "\n__NVPN_CODE__"); i >= 0 {
		body = text[:i]
		rest := text[i+len("\n__NVPN_CODE__"):]
		codeLine, urlPart, _ := strings.Cut(rest, "\n__NVPN_URL__")
		codeLine = strings.TrimSpace(codeLine)
		if n := atoiSafe(codeLine); n > 0 {
			code = n
		}
		if u := strings.TrimSpace(urlPart); u != "" {
			final = u
		}
	}
	if len(body) > netcheckMaxBody {
		body = body[:netcheckMaxBody]
	}
	return fetchResult{Status: code, Body: body, FinalURL: final}
}

func atoiSafe(s string) int {
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0
		}
		n = n*10 + int(c-'0')
	}
	return n
}
