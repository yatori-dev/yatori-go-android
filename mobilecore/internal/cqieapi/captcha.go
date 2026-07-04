package cqieapi

import (
	"crypto/tls"
	"encoding/base64"
	"io/ioutil"
	"net/http"

	gouuid "github.com/google/uuid"
	"github.com/yatori-dev/yatori-go-mobile-core/utils"
)

// FetchCaptchaBase64 fetches a cqie captcha image without writing to disk.
// Returns (imageBase64, cookie, uuid, error). Does not depend on CqieUserCache.
func FetchCaptchaBase64() (string, string, string, error) {
	uuidStr := gouuid.New().String()
	urlStr := "https://study.cqie.edu.cn/gateway/auth/createCaptcha?uuid=" + uuidStr

	tr := &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
	req, err := http.NewRequest("GET", urlStr, nil)
	if err != nil {
		return "", "", "", err
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	resp, err := (&http.Client{Transport: tr}).Do(req)
	if err != nil {
		return "", "", "", err
	}
	defer resp.Body.Close()
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return "", "", "", err
	}
	return base64.StdEncoding.EncodeToString(body), resp.Header.Get("Set-Cookie"), uuidStr, nil
}
