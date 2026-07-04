package mobile

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"mime/multipart"
	"net/http"
	"strings"
)

// GetCaptchaBytes fetches the CAPTCHA image from /service/code.
// Returns raw PNG bytes and session cookies (forward both to LoginWithCode).
func GetCaptchaBytes(preURL string) (imgBytes []byte, cookies []*http.Cookie, err error) {
	r := fmt.Sprintf("%.16f", rand.Float64())
	req, err := http.NewRequest("GET", preURL+"/service/code?r="+r, nil)
	if err != nil {
		return nil, nil, err
	}
	req.Header.Set("User-Agent", mobileUA)
	resp, err := httpClient().Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	return b, resp.Cookies(), err
}

// LoginWithCode submits credentials + captcha answer using the session cookies
// from GetCaptchaBytes. Returns (token, sign) on success.
func LoginWithCode(preURL, account, password, code string, cookies []*http.Cookie) (token, sign string, err error) {
	buf := &bytes.Buffer{}
	w := multipart.NewWriter(buf)
	w.WriteField("username", account)
	w.WriteField("password", password)
	w.WriteField("code", code)
	w.WriteField("redirect", preURL)
	w.Close()

	req, err := http.NewRequest("POST", preURL+"/user/login.json", buf)
	if err != nil {
		return "", "", err
	}
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("User-Agent", mobileUA)
	for _, ck := range cookies {
		req.AddCookie(ck)
	}
	resp, err := httpClient().Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return ParseLoginResponse(string(body))
}

// ParseLoginResponse extracts token and sign from a yinghua login JSON response.
// Exported for unit tests.
func ParseLoginResponse(raw string) (token, sign string, err error) {
	var resp struct {
		Redirect string `json:"redirect"`
		Msg      string `json:"msg"`
		Status   bool   `json:"status"`
	}
	if jsonErr := json.Unmarshal([]byte(raw), &resp); jsonErr != nil {
		return "", "", fmt.Errorf("yinghua: login parse error: %w", jsonErr)
	}
	if resp.Redirect == "" {
		return "", "", fmt.Errorf("yinghua: login failed: %s", resp.Msg)
	}
	// redirect URL contains "…token=<token>&sign=<sign>"
	parts := strings.SplitN(resp.Redirect, "token=", 2)
	if len(parts) < 2 {
		return "", "", fmt.Errorf("yinghua: token not found in redirect: %s", raw)
	}
	token = strings.SplitN(parts[1], "&", 2)[0]
	if signParts := strings.SplitN(resp.Redirect, "&sign=", 2); len(signParts) >= 2 {
		sign = signParts[1]
	}
	return token, sign, nil
}
