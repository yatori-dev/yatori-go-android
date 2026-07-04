package mobile

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
)

var xxtCookieJarURLs = []string{
	"https://passport2.chaoxing.com/",
	"http://i.mooc.chaoxing.com/",
	"https://i.mooc.chaoxing.com/",
	"https://mooc1.chaoxing.com/",
	"https://mooc1-api.chaoxing.com/",
	"https://stat2-ans.chaoxing.com/",
}

var xxtLoginWarmupURLs = []string{
	"https://passport2.chaoxing.com/login?refer=http%3A%2F%2Fi.mooc.chaoxing.com",
	"http://i.mooc.chaoxing.com/",
	"https://i.mooc.chaoxing.com/",
	"https://mooc1.chaoxing.com/visit/courses",
	"https://stat2-ans.chaoxing.com/",
}

func aesCBCEncrypt(plain string) (string, error) {
	key := []byte(xxtAESKey)
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	padding := block.BlockSize() - len(plain)%block.BlockSize()
	src := append([]byte(plain), bytes.Repeat([]byte{byte(padding)}, padding)...)
	dst := make([]byte, len(src))
	cipher.NewCBCEncrypter(block, key).CryptBlocks(dst, src)
	return base64.StdEncoding.EncodeToString(dst), nil
}

// LoginApi logs in to chaoxing and populates c.Cookies. It mirrors go-core's
// fanyalogin payload, but uses a PC browser UA plus CookieJar warmup so cookies
// set during redirects are not lost.
func (c *XxtClient) LoginApi(retry int) (string, error) {
	return c.loginApi(retry, nil)
}

func (c *XxtClient) loginApi(retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	phoneEnc, err := aesCBCEncrypt(c.Name)
	if err != nil {
		return "", err
	}
	passEnc, err := aesCBCEncrypt(c.Password)
	if err != nil {
		return "", err
	}

	jar, err := cookiejar.New(nil)
	if err != nil {
		return "", err
	}
	seedXxtCookieJar(c, jar)
	client := &http.Client{Transport: httpTransport(c), Jar: jar}
	_ = xxtPCGet(client, xxtLoginWarmupURLs[0])

	form := url.Values{
		"fid": {"-1"}, "uname": {phoneEnc}, "password": {passEnc},
		"refer": {"http%3A%2F%2Fi.mooc.chaoxing.com"}, "t": {"true"},
		"forbidotherlogin": {"0"}, "validate": {""},
		"doubleFactorLogin": {"0"}, "independentId": {"0"}, "independentNameId": {"0"},
	}
	req, err := http.NewRequest("POST", "https://passport2.chaoxing.com/fanyalogin", strings.NewReader(form.Encode()))
	if err != nil {
		return c.loginApi(retry-1, err)
	}
	addPCBrowserHeaders(req, "https://passport2.chaoxing.com", xxtLoginWarmupURLs[0])
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := client.Do(req)
	if err != nil {
		return c.loginApi(retry-1, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return c.loginApi(retry-1, err)
	}
	raw := string(body)
	collectXxtCookiesFromJar(c, jar)
	mergeCookies(c, resp.Cookies())
	if strings.Contains(raw, "寰堟姳姝夛紝鎮ㄦ墍娴忚鐨勯〉闈㈡殏鏃朵笉鑳借闂紒") ||
		strings.Contains(raw, "很抱歉，您所浏览的页面暂时不能访问") {
		return c.loginApi(retry-1, err)
	}
	if strings.Contains(raw, `"status":true`) {
		for _, warmupURL := range xxtLoginWarmupURLs[1:] {
			_ = xxtPCGet(client, warmupURL)
		}
		collectXxtCookiesFromJar(c, jar)
	}
	return raw, nil
}

func seedXxtCookieJar(c *XxtClient, jar *cookiejar.Jar) {
	if len(c.Cookies) == 0 {
		return
	}
	for _, rawURL := range xxtCookieJarURLs {
		u, err := url.Parse(rawURL)
		if err == nil {
			jar.SetCookies(u, c.Cookies)
		}
	}
}

func collectXxtCookiesFromJar(c *XxtClient, jar *cookiejar.Jar) {
	index := make(map[string]int, len(c.Cookies))
	for i, ck := range c.Cookies {
		index[ck.Name] = i
	}
	for _, rawURL := range xxtCookieJarURLs {
		u, err := url.Parse(rawURL)
		if err != nil {
			continue
		}
		for _, ck := range jar.Cookies(u) {
			if i, ok := index[ck.Name]; ok {
				c.Cookies[i] = ck
				continue
			}
			index[ck.Name] = len(c.Cookies)
			c.Cookies = append(c.Cookies, ck)
		}
	}
}

func xxtPCGet(client *http.Client, rawURL string) error {
	req, err := http.NewRequest("GET", rawURL, nil)
	if err != nil {
		return err
	}
	addPCBrowserHeaders(req, "", "")
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
	req.Header.Set("Cache-Control", "max-age=0")
	req.Header.Set("Upgrade-Insecure-Requests", "1")
	req.Header.Set("Sec-Fetch-Site", "none")
	req.Header.Set("Sec-Fetch-Mode", "navigate")
	req.Header.Set("Sec-Fetch-User", "?1")
	req.Header.Set("Sec-Fetch-Dest", "document")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return nil
}
