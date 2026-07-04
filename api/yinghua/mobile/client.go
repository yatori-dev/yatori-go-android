package mobile

import (
	"bytes"
	"crypto/tls"
	"io"
	"mime/multipart"
	"net/http"
	"time"
)

const mobileUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

// YingHuaClient holds an authenticated yinghua session for one user.
type YingHuaClient struct {
	PreURL  string
	Token   string
	Sign    string
	Cookies []*http.Cookie
}

func httpClient() *http.Client {
	return &http.Client{
		Timeout:   30 * time.Second,
		Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}},
	}
}

// postForm sends a multipart/form-data POST, returns body bytes.
func (c *YingHuaClient) postForm(urlStr string, fields map[string]string) ([]byte, error) {
	buf := &bytes.Buffer{}
	w := multipart.NewWriter(buf)
	for k, v := range fields {
		if err := w.WriteField(k, v); err != nil {
			return nil, err
		}
	}
	w.Close()
	req, err := http.NewRequest("POST", urlStr, buf)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("User-Agent", mobileUA)
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
	resp, err := httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// postMultipartOrdered sends a multipart POST preserving the order and repetition
// of field keys (yinghua submit needs repeated "answer[]" keys). fields is a slice
// of [key, value] pairs.
func (c *YingHuaClient) postMultipartOrdered(urlStr string, fields [][2]string) ([]byte, error) {
	buf := &bytes.Buffer{}
	w := multipart.NewWriter(buf)
	for _, kv := range fields {
		if err := w.WriteField(kv[0], kv[1]); err != nil {
			return nil, err
		}
	}
	w.Close()
	req, err := http.NewRequest("POST", urlStr, buf)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("User-Agent", mobileUA)
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
	resp, err := httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// get sends a GET request and returns body bytes.
func (c *YingHuaClient) get(urlStr string) ([]byte, error) {
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", mobileUA)
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
	resp, err := httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// postJSON sends an application/json POST with the given body, returns body bytes.
func (c *YingHuaClient) postJSON(urlStr, jsonBody string) ([]byte, error) {
	req, err := http.NewRequest("POST", urlStr, bytes.NewReader([]byte(jsonBody)))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("User-Agent", mobileUA)
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
	resp, err := httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}
