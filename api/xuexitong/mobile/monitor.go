package mobile

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"time"
)

func MonitorURL(fid, callback string, nowMillis int64) string {
	if callback == "" {
		callback = "jsonp" + strconv.FormatInt(nowMillis%100000000000000000, 10)
	}
	q := url.Values{}
	q.Set("version", "1748956725820")
	q.Set("refer", "http%253A%252F%252Fi.mooc.chaoxing.com")
	q.Set("from", "")
	q.Set("fid", fid)
	q.Set("jsoncallback", callback)
	q.Set("t", strconv.FormatInt(nowMillis, 10))
	return "https://detect.chaoxing.com/api/monitor?" + q.Encode()
}

func ParseMonitorResult(raw string) (alive, expired bool) {
	if raw == "" {
		return false, false
	}
	if regexp.MustCompile(`(?i)"?status"?\s*:\s*(true|1)`).MatchString(raw) ||
		regexp.MustCompile(`(?i)"?result"?\s*:\s*(true|1)`).MatchString(raw) {
		return true, false
	}
	if regexp.MustCompile(`(?i)"?status"?\s*:\s*(false|0)`).MatchString(raw) ||
		regexp.MustCompile(`(?i)"?result"?\s*:\s*(false|0)`).MatchString(raw) {
		return false, true
	}
	return false, false
}

func (c *XxtClient) MonitorApi() (string, error) {
	fid := c.cookieValue("fid")
	req, err := http.NewRequest("GET", MonitorURL(fid, "", time.Now().UnixMilli()), nil)
	if err != nil {
		return "", err
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "detect.chaoxing.com")
	req.Header.Add("Connection", "keep-alive")
	res, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	mergeCookies(c, res.Cookies())
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	if res.StatusCode >= 400 {
		return string(body), fmt.Errorf("xuexitong: monitor status %d", res.StatusCode)
	}
	return string(body), nil
}
