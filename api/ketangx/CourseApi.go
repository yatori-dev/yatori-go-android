package ketangx

import (
	"crypto/tls"
	"fmt"
	"io/ioutil"
	"net/http"
	"strings"
	"time"

	"github.com/yatori-dev/yatori-go-mobile-core/utils"
)

var ketangxHTTPClientFactory = func() *http.Client {
	return &http.Client{Transport: &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true, // 跳过证书验证，仅用于开发环境
		},
	}}
}

func ketangxProtectedResponseError(res *http.Response, body []byte) error {
	if res.StatusCode == http.StatusUnauthorized || res.StatusCode == http.StatusForbidden {
		return fmt.Errorf("ketangx: 账号登录超时，请重新登录")
	}
	if res.Request != nil && res.Request.URL != nil && strings.Contains(strings.ToLower(res.Request.URL.Path), "/login") {
		return fmt.Errorf("ketangx: 账号登录超时，请重新登录")
	}
	lowerBody := strings.ToLower(string(body))
	if strings.Contains(lowerBody, "/login/acclogin") &&
		strings.Contains(lowerBody, "useraccount") &&
		strings.Contains(lowerBody, "password") {
		return fmt.Errorf("ketangx: 账号登录超时，请重新登录")
	}
	if res.StatusCode < http.StatusOK || res.StatusCode >= http.StatusMultipleChoices {
		return fmt.Errorf("ketangx: unexpected HTTP status %d", res.StatusCode)
	}
	return nil
}

// 拉取课程对应列表HTML
func (cache *KetangxUserCache) PullCourseListHTMLApi() (string, error) {
	urlStr := "https://www.ketangx.cn/Activity/Query"
	method := "POST"

	payload := strings.NewReader("actType=2&actStart=&actClose=&formId=&classId=&actKey=&actState=&timeId=" + fmt.Sprintf("%d", time.Now().UnixMilli()))

	client := ketangxHTTPClientFactory()
	req, err := http.NewRequest(method, urlStr, payload)
	if err != nil {
		return "", fmt.Errorf("ketangx: create course-list request: %w", err)
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ketangx.cn")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}

	res, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("ketangx: course-list request failed: %w", err)
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", fmt.Errorf("ketangx: read course-list response: %w", err)
	}
	if err := ketangxProtectedResponseError(res, body); err != nil {
		return "", err
	}
	cache.Cookies = res.Cookies()

	//fmt.Println(string(body))
	return string(body), nil
}

// 拉取课程对应视频列表HTML
func (cache *KetangxUserCache) PullVideoListHTMLApi(courseId string) (string, error) {

	urlStr := "https://www.ketangx.cn/DoAct/ActIndex/" + courseId + "?_=" + fmt.Sprintf("%d", time.Now().UnixMilli())
	method := "GET"

	client := ketangxHTTPClientFactory()
	req, err := http.NewRequest(method, urlStr, nil)
	if err != nil {
		return "", fmt.Errorf("ketangx: create node-list request: %w", err)
	}
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ketangx.cn")
	req.Header.Add("Connection", "keep-alive")

	res, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("ketangx: node-list request failed: %w", err)
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", fmt.Errorf("ketangx: read node-list response: %w", err)
	}
	if err := ketangxProtectedResponseError(res, body); err != nil {
		return "", err
	}
	return string(body), nil
}

// 标记任务点状态API，视频学习前必须要先调用这个
func (cache *KetangxUserCache) SignVideoStatusApi(sectId string) (string, error) {

	urlStr := "https://www.ketangx.cn/DoAct/GetSection?id=" + sectId + "&_=" + fmt.Sprintf("%d", time.Now().UnixMilli())
	method := "GET"

	client := ketangxHTTPClientFactory()
	req, err := http.NewRequest(method, urlStr, nil)

	if err != nil {
		fmt.Println(err)
		return "", err
	}
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ketangx.cn")
	req.Header.Add("Connection", "keep-alive")

	res, err := client.Do(req)
	if err != nil {
		fmt.Println(err)
		return "", err
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		fmt.Println(err)
		return "", err
	}
	if err := ketangxProtectedResponseError(res, body); err != nil {
		return "", err
	}
	return string(body), nil
}

// 完成视频任务点API
func (cache *KetangxUserCache) CompleteVideoApi(sectId, submissionID string, studyTime, duration int) (string, error) {

	urlStr := "https://www.ketangx.cn/Common/SetDuration"
	method := "POST"

	payload := strings.NewReader("studyData%5BSectId%5D=" + sectId + "&studyData%5BUserId%5D=" + submissionID + "&studyData%5BStudyTime%5D=" + fmt.Sprintf("%d", studyTime) + "&studyData%5BDuraion%5D=" + fmt.Sprintf("%d", duration))

	client := ketangxHTTPClientFactory()
	req, err := http.NewRequest(method, urlStr, payload)
	if err != nil {
		return "", fmt.Errorf("ketangx: create complete request: %w", err)
	}
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ketangx.cn")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")

	res, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("ketangx: complete request failed: %w", err)
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", fmt.Errorf("ketangx: read complete response: %w", err)
	}
	if err := ketangxProtectedResponseError(res, body); err != nil {
		return "", err
	}
	return string(body), nil
}
