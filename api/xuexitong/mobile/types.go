package mobile

import (
	"crypto/md5"
	"crypto/tls"
	"fmt"
	"net/http"
	"strings"
)

const (
	xxtAESKey     = "u2oh6Vu^HWe4_AES"
	xxtAppVersion = "6.7.2"
	xxtBuild      = "10941_314"
	xxtDevice     = "MI10"
	xxtAndroid    = "Android 16"
	xxtUASalt     = "ipL$TkeiEmfy1gTXb2XHrdLN0a@7c^vu"
)

// XxtClient holds a mobile-safe session for xuexitong (chaoxing).
type XxtClient struct {
	Name     string
	Password string
	UserID   string
	Cookies  []*http.Cookie
}

// CookieStr returns "name=value; ..." for header use.
func (c *XxtClient) CookieStr() string {
	parts := make([]string, 0, len(c.Cookies))
	for _, ck := range c.Cookies {
		parts = append(parts, ck.Name+"="+ck.Value)
	}
	return strings.Join(parts, "; ")
}

func httpTransport(c *XxtClient) *http.Transport {
	return &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
}

func mobileUA() string {
	sign := mobileUASign(xxtDevice, "zh_CN", xxtAppVersion, xxtBuild, xxtIMEI)
	return fmt.Sprintf(
		"Mozilla/5.0 (Linux; %s; %s Build/OPM1.171019.019; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.99 Mobile Safari/537.36 (schild:%s) (device:%s) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_%s_android_phone_%s (@Kalimdor)_%s",
		xxtAndroid, xxtDevice, sign, xxtDevice, xxtAppVersion, xxtBuild, xxtIMEI,
	)
}

func mobileUASign(model, locale, version, build, imei string) string {
	src := strings.Join([]string{
		"(schild:" + xxtUASalt + ")",
		fmt.Sprintf("(device:%s)", model),
		fmt.Sprintf("Language/%s", locale),
		fmt.Sprintf("com.chaoxing.mobile/ChaoXingStudy_3_%s_android_phone_%s", version, build),
		fmt.Sprintf("(@Kalimdor)_%s", imei),
	}, " ")
	return fmt.Sprintf("%x", md5.Sum([]byte(src)))
}

func pcUA() string {
	return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
}

func addPCBrowserHeaders(req *http.Request, origin, referer string) {
	req.Header.Set("User-Agent", pcUA())
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
	req.Header.Set("sec-ch-ua", `"Microsoft Edge";v="143", "Chromium";v="143", "Not A(Brand";v="24"`)
	req.Header.Set("sec-ch-ua-mobile", "?0")
	req.Header.Set("sec-ch-ua-platform", `"Windows"`)
	req.Header.Set("Connection", "keep-alive")
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
}

func addCookies(req *http.Request, c *XxtClient) {
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
}

func mergeCookies(c *XxtClient, incoming []*http.Cookie) {
	seen := make(map[string]bool, len(c.Cookies))
	for _, ck := range c.Cookies {
		seen[ck.Name] = true
	}
	for _, ck := range incoming {
		if !seen[ck.Name] {
			c.Cookies = append(c.Cookies, ck)
			seen[ck.Name] = true
		}
	}
}
