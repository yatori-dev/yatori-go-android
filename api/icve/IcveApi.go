package icve

import (
	"bytes"
	"crypto/aes"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

const (
	ssoBase  = "https://sso.icve.com.cn/prod-api"
	zykBase  = "https://zyk.icve.com.cn/prod-api"
	defaultUA = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36"
	aesKey    = "djekiytolkijduey"
)

// IcveUserCache holds session data for a 智慧职教 (ICVE/ZYK) account.
type IcveUserCache struct {
	Token          string // sso.icve token
	ZYKAccessToken string // zyk.icve JWT
	UserId         string
	Cookies        []*http.Cookie
}

func (c *IcveUserCache) httpClient() *http.Client {
	return &http.Client{
		Timeout:   30 * time.Second,
		Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}},
	}
}

func (c *IcveUserCache) authHeader() string { return "Bearer " + c.ZYKAccessToken }

func (c *IcveUserCache) zykGet(path string) (string, error) {
	req, err := http.NewRequest("GET", zykBase+path, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", defaultUA)
	req.Header.Set("Authorization", c.authHeader())
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	return string(b), err
}

// ZYKAccessTokenApi exchanges the SSO token for a ZYK (资源库) JWT.
// POST https://zyk.icve.com.cn/prod-api/auth/passLogin?token=<token>
func (c *IcveUserCache) ZYKAccessTokenApi() (string, error) {
	req, err := http.NewRequest("GET", zykBase+"/auth/passLogin?token="+c.Token, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", defaultUA)
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	for _, ck := range resp.Cookies() {
		c.Cookies = appendCookieNoRepeat(c.Cookies, ck)
	}
	return string(b), nil
}

// ZYKPullUserInfoApi fetches the user profile (userId etc.).
// GET https://zyk.icve.com.cn/prod-api/system/user/getInfo
func (c *IcveUserCache) ZYKPullUserInfoApi() (string, error) {
	return c.zykGet("/system/user/getInfo")
}

// ZYKCourseListApi returns the student's ZYK course list.
// GET https://zyk.icve.com.cn/prod-api/teacher/courseList/myCourseList?pageSize=100&pageNum=1&flag=1
func (c *IcveUserCache) ZYKCourseListApi() (string, error) {
	return c.zykGet("/teacher/courseList/myCourseList?pageSize=100&pageNum=1&flag=1")
}

// ZYKRootNodeListApi fetches top-level chapter modules for a course.
// GET https://zyk.icve.com.cn/prod-api/teacher/courseContent/studyMoudleList?courseInfoId=<id>
func (c *IcveUserCache) ZYKRootNodeListApi(courseInfoId string) (string, error) {
	return c.zykGet("/teacher/courseContent/studyMoudleList?courseInfoId=" + courseInfoId)
}

// ZYKNodeListApi fetches child nodes at the given level under a parent.
// GET https://zyk.icve.com.cn/prod-api/teacher/courseContent/studyList?level=<l>&parentId=<p>&courseInfoId=<c>
func (c *IcveUserCache) ZYKNodeListApi(level int, parentId, courseInfoId string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	path := fmt.Sprintf("/teacher/courseContent/studyList?level=%d&parentId=%s&courseInfoId=%s", level, parentId, courseInfoId)
	raw, err := c.zykGet(path)
	if err != nil {
		return c.ZYKNodeListApi(level, parentId, courseInfoId, retry-1, err)
	}
	return raw, nil
}

// ZYKNodeInfoApi fetches detailed info (duration, fileUrl) for a leaf node.
// GET https://zyk.icve.com.cn/prod-api/teacher/courseContent/<id>
func (c *IcveUserCache) ZYKNodeInfoApi(id string) (string, error) {
	return c.zykGet("/teacher/courseContent/" + id)
}

// ZYKSubmitStudyTimeApi records study progress for a node.
// PUT https://zyk.icve.com.cn/prod-api/teacher/studyRecord  (AES-ECB encrypted body)
func (c *IcveUserCache) ZYKSubmitStudyTimeApi(courseInfoId, parentId string, studyTime int, sourceId, studentId string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	params := map[string]interface{}{
		"courseInfoId": courseInfoId,
		"id":           "",
		"parentId":     parentId,
		"studyTime":    fmt.Sprintf("%d", studyTime),
		"sourceId":     sourceId,
		"studentId":    studentId,
		"actualNum":    fmt.Sprintf("%d", studyTime),
		"lastNum":      fmt.Sprintf("%d", studyTime),
		"totalNum":     fmt.Sprintf("%d", studyTime),
	}
	jsonBytes, _ := json.Marshal(params)
	enc, err := aesEncryptECB([]byte(aesKey), string(jsonBytes))
	if err != nil {
		return "", fmt.Errorf("icve: AES encrypt failed: %w", err)
	}
	req, err := http.NewRequest("PUT", zykBase+"/teacher/studyRecord", bytes.NewBufferString(enc))
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", defaultUA)
	req.Header.Set("Authorization", c.authHeader())
	for _, ck := range c.Cookies {
		req.AddCookie(ck)
	}
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return c.ZYKSubmitStudyTimeApi(courseInfoId, parentId, studyTime, sourceId, studentId, retry-1, err)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// aesEncryptECB encrypts plaintext with AES/ECB/PKCS7 and returns Base64.
func aesEncryptECB(key []byte, plaintext string) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	padding := block.BlockSize() - len(plaintext)%block.BlockSize()
	padtext := bytes.Repeat([]byte{byte(padding)}, padding)
	padded := append([]byte(plaintext), padtext...)
	ciphertext := make([]byte, len(padded))
	for bs, be := 0, block.BlockSize(); bs < len(padded); bs, be = bs+block.BlockSize(), be+block.BlockSize() {
		block.Encrypt(ciphertext[bs:be], padded[bs:be])
	}
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// appendCookieNoRepeat adds a cookie only if its name is not already in the slice.
func appendCookieNoRepeat(cookies []*http.Cookie, c *http.Cookie) []*http.Cookie {
	for _, existing := range cookies {
		if existing.Name == c.Name {
			existing.Value = c.Value
			return cookies
		}
	}
	return append(cookies, c)
}

// ParseCookieString converts a "name=value; name2=value2" string into http.Cookie slice.
func ParseCookieString(s string) []*http.Cookie {
	var out []*http.Cookie
	for _, part := range splitCookies(s) {
		idx := indexOf(part, '=')
		if idx < 0 {
			continue
		}
		out = append(out, &http.Cookie{Name: trimSpace(part[:idx]), Value: trimSpace(part[idx+1:])})
	}
	return out
}

func splitCookies(s string) []string {
	var parts []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == ';' {
			parts = append(parts, trimSpace(s[start:i]))
			start = i + 1
		}
	}
	if start < len(s) {
		parts = append(parts, trimSpace(s[start:]))
	}
	return parts
}

func indexOf(s string, b byte) int {
	for i := 0; i < len(s); i++ {
		if s[i] == b {
			return i
		}
	}
	return -1
}

func trimSpace(s string) string {
	start, end := 0, len(s)
	for start < end && (s[start] == ' ' || s[start] == '\t') {
		start++
	}
	for end > start && (s[end-1] == ' ' || s[end-1] == '\t') {
		end--
	}
	return s[start:end]
}
