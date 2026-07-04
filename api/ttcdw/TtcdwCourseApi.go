package ttcdw

import (
	"bytes"
	"crypto/des"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/yatori-dev/yatori-go-mobile-core/utils"
)

func (cache *TtcdwUserCache) PullProjectApi(retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	urlStr := "https://www.ttcdw.cn/m/open/app/v1/memProject/list?state=1&pageNum=1&pageSize=100"
	method := "GET"

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, nil)

	if err != nil {
		//fmt.Println(err)
		return "", err
	}
	//设置Cookie
	for _, v := range cache.Cookies {
		req.AddCookie(v)
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ttcdw.cn")
	req.Header.Add("Connection", "keep-alive")

	res, err := client.Do(req)
	if err != nil {
		time.Sleep(150 * time.Millisecond)
		return cache.PullProjectApi(retry-1, err)
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		fmt.Println(err)
		return "", err
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, req.Cookies())
	return string(body), nil
}

// 拉取项目的课堂内容，比如必修或者非必修
func (cache *TtcdwUserCache) PullClassRoomApi(courseProjectId string, classId string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}

	urlStr := "https://www.ttcdw.cn/m/open/app/v2/member/project/" + courseProjectId + "/segment?classId=" + classId
	method := "GET"

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, nil)

	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ttcdw.cn")
	req.Header.Add("Connection", "keep-alive")

	res, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	//fmt.Println(string(body))
	utils.CookiesAddNoRepetition(&cache.Cookies, req.Cookies())
	return string(body), nil
}
func (cache *TtcdwUserCache) PullCourseInfoApi(segmentId, courseId string, retry int, lastErr error) (string, error) {

	urlStr := "https://www.ttcdw.cn/m/open/app/v1/course/basic/" + courseId + "?segId=" + segmentId
	method := "GET"

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, nil)

	if err != nil {
		return "", nil
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ttcdw.cn")
	req.Header.Add("Connection", "keep-alive")
	//设置Cookie
	for _, v := range cache.Cookies {
		req.AddCookie(v)
	}
	res, err := client.Do(req)
	if err != nil {
		return "", nil
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", nil
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, req.Cookies())
	return string(body), nil
}

func (cache *TtcdwUserCache) PullCourseApi(segmentId, itemId string, retry int, lastErr error) (string, error) {

	urlStr := "https://www.ttcdw.cn/m/open/app/v1/items/bxk/course/list?types=&segmentId=" + segmentId + "&itemId=" + itemId + "&moduleId=&pageNum=1&pageSize=100"
	method := "GET"

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, nil)

	if err != nil {
		return "", err
	}
	//设置Cookie
	for _, v := range cache.Cookies {
		req.AddCookie(v)
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "www.ttcdw.cn")
	req.Header.Add("Connection", "keep-alive")

	res, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, req.Cookies())
	return string(body), nil
}

// 拉取项目对应课程章节列表
func (cache *TtcdwUserCache) PullChapterListHtmlApi(cid string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	urlStr := "https://service.icourses.cn/hep-company/sword/company/shareChapter?cid=" + cid + "&shield="
	method := "GET"

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, nil)

	if err != nil {
		return "", err
	}
	//设置Cookie
	for _, v := range cache.Cookies {
		req.AddCookie(v)
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "service.icourses.cn")
	req.Header.Add("Connection", "keep-alive")

	res, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, req.Cookies())
	return string(body), nil
}

// 获取章节secId对应的子章节内容json
func (cache *TtcdwUserCache) PullGetResApi(sectionId string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	urlStr := "https://service.icourses.cn/hep-company//sword/company/getRess"
	method := "POST"

	payload := strings.NewReader("sectionId=" + sectionId)

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, payload)

	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "service.icourses.cn")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")
	//设置Cookie
	for _, v := range cache.Cookies {
		req.AddCookie(v)
	}

	res, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, req.Cookies())
	return string(body), nil
}

// 拉取视频列表
func (cache *TtcdwUserCache) PullVideoListApi(courseId, itemId, segId, projectId, orgId string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	urlStr := "https://www.ttcdw.cn/p/course/services/course/public/course/lesson/" + courseId + "?ddtab=true&itemId=" + itemId + "&segId=" + segId + "&projectId=" + projectId + "&orgId=" + orgId + "&orgId=" + orgId + "&type=1&courseType=1&courseId=" + courseId + "&id=" + courseId + "&isContent=false&sourceType=1&_=" + fmt.Sprintf("%d", time.Now().UnixMilli())
	method := "GET"

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, nil)

	if err != nil {
		fmt.Println(err)
		return "", err
	}
	req.Header.Add("accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Add("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
	req.Header.Add("cache-control", "no-cache")
	req.Header.Add("pragma", "no-cache")
	req.Header.Add("priority", "u=1, i")
	//req.Header.Add("referer", "https://www.ttcdw.cn/p/course/videorevision/v_895438431542083584?ddtab=true&itemId=1033511805027008512&segId=1033511477548335104&projectId=1033502195012517888&orgId=171864496496529408&type=1&courseType=1")
	req.Header.Add("sec-ch-ua", "\"Microsoft Edge\";v=\"141\", \"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"141\"")
	req.Header.Add("sec-ch-ua-mobile", "?0")
	req.Header.Add("sec-ch-ua-platform", "\"Windows\"")
	req.Header.Add("sec-fetch-dest", "empty")
	req.Header.Add("sec-fetch-mode", "cors")
	req.Header.Add("sec-fetch-site", "same-origin")
	req.Header.Add("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0")
	req.Header.Add("x-requested-with", "XMLHttpRequest")
	req.Header.Add("content-type", "application/x-www-form-urlencoded")
	req.Header.Add("Host", "www.ttcdw.cn")
	req.Header.Add("Connection", "keep-alive")
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}

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
	//fmt.Println(string(body))
	return string(body), nil
}

// 提交学时接口
func (cache *TtcdwUserCache) StudyTimeSubmitApi(orgId, courseId, itemId, videoId string, playProgress int, segId string, isFinish bool, typeNum, tjzj, clockInDot, sourceId, clockInRule, eventType string, retry int, lastErr error) (string, error) {

	urlStr := "https://www.ttcdw.cn/p/course/services/member/study/progress?orgId=" + orgId
	method := "POST"

	if typeNum == "" {
		typeNum = "1"
	}
	if tjzj == "" {
		tjzj = "1"
	}
	if clockInDot == "" {
		clockInDot = "599"
	}
	if sourceId == "" {
		sourceId = "1033502195012517888"
	}
	if clockInRule == "" {
		clockInRule = "0"
	}
	form := url.Values{}
	form.Set("courseId", courseId)
	form.Set("itemId", itemId)
	form.Set("videoId", videoId)
	form.Set("playProgress", fmt.Sprintf("%d", playProgress))
	form.Set("segId", segId)
	form.Set("isFinish", fmt.Sprintf("%t", isFinish))
	form.Set("type", typeNum)
	form.Set("tjzj", tjzj)
	form.Set("clockInDot", clockInDot)
	form.Set("sourceId", sourceId)
	form.Set("clockInRule", clockInRule)
	form.Set("timeLimit", "-1")
	form.Set("eventType", eventType)
	payload := strings.NewReader(form.Encode())

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: tr,
	}
	req, err := http.NewRequest(method, urlStr, payload)

	if err != nil {
		fmt.Println(err)
		return "", err
	}
	req.Header.Add("accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Add("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
	req.Header.Add("cache-control", "no-cache")
	req.Header.Add("encryptionvalue", courseId)
	req.Header.Add("isencryption", "true")
	req.Header.Add("origin", "https://www.ttcdw.cn")
	req.Header.Add("pragma", "no-cache")
	req.Header.Add("priority", "u=0, i")
	req.Header.Add("referer", "https://www.ttcdw.cn/p/course/videorevision/v_"+videoId+"?ddtab=true&itemId="+itemId+"&segId="+segId+"&projectId="+sourceId+"&orgId="+orgId+"&type="+typeNum+"&courseType=2")
	req.Header.Add("sec-ch-ua", "\"Microsoft Edge\";v=\"141\", \"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"141\"")
	req.Header.Add("sec-ch-ua-mobile", "?0")
	req.Header.Add("sec-ch-ua-platform", "\"Windows\"")
	req.Header.Add("sec-fetch-dest", "empty")
	req.Header.Add("sec-fetch-mode", "cors")
	req.Header.Add("sec-fetch-site", "same-origin")
	req.Header.Add("u-platformid", "13145854983311")
	req.Header.Add("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0")
	req.Header.Add("x-requested-with", "XMLHttpRequest")
	req.Header.Add("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
	req.Header.Add("Host", "www.ttcdw.cn")
	req.Header.Add("Connection", "keep-alive")
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}

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
	//fmt.Println(string(body))
	return string(body), nil
}

// PKCS7 填充
type TickerPayload struct {
	CompanyCode string      `json:"companyCode"`
	UserID      string      `json:"userId"`
	ResID       interface{} `json:"resId"`
	CourseID    string      `json:"courseId"`
	CourseType  string      `json:"courseType"`
	TickerTime  int64       `json:"tickerTime"`
	MD5         string      `json:"md5"`
}

func BuildTickerData(companyCode, userID string, resID interface{}, courseID, courseType string, tickerTime int64, playedRanges string) (string, TickerPayload, error) {
	if courseType == "" {
		courseType = "share"
	}
	md5, err := EncData(playedRanges)
	if err != nil {
		return "", TickerPayload{}, err
	}
	payload := TickerPayload{
		CompanyCode: companyCode,
		UserID:      userID,
		ResID:       resID,
		CourseID:    courseID,
		CourseType:  courseType,
		TickerTime:  tickerTime,
		MD5:         md5,
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return "", TickerPayload{}, err
	}
	tickerData, err := EncData(string(b))
	if err != nil {
		return "", TickerPayload{}, err
	}
	return tickerData, payload, nil
}

func ParseStudySubmitResult(raw string) (bool, string, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return false, "", err
	}
	msg := ""
	for _, key := range []string{"message", "msg", "resultMsg"} {
		if s, ok := obj[key].(string); ok {
			msg = s
			break
		}
	}
	if success, ok := obj["success"].(bool); ok {
		return success, msg, nil
	}
	for _, key := range []string{"resultCode", "code"} {
		switch v := obj[key].(type) {
		case float64:
			return v == 0 || v == 200, msg, nil
		case string:
			return v == "0" || v == "200", msg, nil
		}
	}
	return true, msg, nil
}

type CourseParam struct {
	ClockInRule string                 `json:"clockInRule"`
	TimeLimit   string                 `json:"timeLimit"`
	Raw         map[string]interface{} `json:"raw,omitempty"`
}

type StudyProgressSubmitOptions struct {
	ProgressURL  string
	OrgID        string
	CourseID     string
	ItemID       string
	VideoID      string
	PlayProgress int
	SegID        string
	IsFinish     bool
	Type         string
	Tjzj         string
	ClockInDot   string
	SourceID     string
	ChapterID    string
	ClockInRule  string
	TimeLimit    string
	EventType    string
	CourseType   string
	PlatformID   string
	Referer      string
}

func StudyProgressURL(orgID string) string {
	u := "https://www.ttcdw.cn/p/course/services/member/study/progress"
	if orgID == "" {
		return u
	}
	return u + "?orgId=" + url.QueryEscape(orgID)
}

func CourseParamURL(projectID, courseID string) string {
	q := url.Values{}
	q.Set("projectId", projectID)
	q.Set("courseId", courseID)
	return "https://www.ttcdw.cn/p/course/services/org/member/study/project/course/param?" + q.Encode()
}

func ParseCourseParam(raw string) (CourseParam, error) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &obj); err != nil {
		return CourseParam{}, err
	}
	data, _ := obj["data"].(map[string]interface{})
	if data == nil {
		return CourseParam{Raw: obj}, nil
	}
	param := CourseParam{Raw: data}
	param.TimeLimit = stringifyTtcdw(data["timeLimit"])
	if cfg, _ := data["clockInConfig"].(map[string]interface{}); cfg != nil {
		param.ClockInRule = stringifyTtcdw(cfg["clockInRule"])
	}
	return param, nil
}

func (cache *TtcdwUserCache) PullCourseParamApi(projectID, courseID string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	req, err := http.NewRequest("GET", CourseParamURL(projectID, courseID), nil)
	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Add("X-Requested-With", "XMLHttpRequest")
	req.Header.Add("Connection", "keep-alive")
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}
	client := ttcdwHTTPClient(cache)
	res, err := client.Do(req)
	if err != nil {
		time.Sleep(150 * time.Millisecond)
		return cache.PullCourseParamApi(projectID, courseID, retry-1, err)
	}
	defer res.Body.Close()
	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, res.Cookies())
	return string(body), nil
}

func (cache *TtcdwUserCache) StudyProgressSubmitApi(opts StudyProgressSubmitOptions, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	opts = normalizeStudyProgressOptions(opts)
	if opts.ProgressURL == "" {
		opts.ProgressURL = StudyProgressURL(opts.OrgID)
	}
	form := url.Values{}
	form.Set("courseId", opts.CourseID)
	form.Set("itemId", opts.ItemID)
	form.Set("videoId", opts.VideoID)
	form.Set("playProgress", fmt.Sprintf("%d", opts.PlayProgress))
	form.Set("segId", opts.SegID)
	form.Set("isFinish", fmt.Sprintf("%t", opts.IsFinish))
	form.Set("type", opts.Type)
	form.Set("tjzj", opts.Tjzj)
	form.Set("clockInDot", opts.ClockInDot)
	form.Set("sourceId", opts.SourceID)
	if opts.ChapterID != "" {
		form.Set("chapterId", opts.ChapterID)
	}
	form.Set("clockInRule", opts.ClockInRule)
	form.Set("timeLimit", opts.TimeLimit)
	form.Set("eventType", opts.EventType)

	req, err := http.NewRequest("POST", opts.ProgressURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Add("Accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Add("Accept-Language", "zh-CN,zh;q=0.9")
	req.Header.Add("Cache-Control", "no-cache")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	req.Header.Add("IsEncryption", "true")
	req.Header.Add("EncryptionValue", opts.CourseID)
	req.Header.Add("Origin", "https://www.ttcdw.cn")
	req.Header.Add("Pragma", "no-cache")
	req.Header.Add("Referer", opts.Referer)
	req.Header.Add("U-Platformid", opts.PlatformID)
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("X-Requested-With", "XMLHttpRequest")
	req.Header.Add("Connection", "keep-alive")
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}
	client := ttcdwHTTPClient(cache)
	res, err := client.Do(req)
	if err != nil {
		time.Sleep(150 * time.Millisecond)
		return cache.StudyProgressSubmitApi(opts, retry-1, err)
	}
	defer res.Body.Close()
	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, res.Cookies())
	return string(body), nil
}

func normalizeStudyProgressOptions(opts StudyProgressSubmitOptions) StudyProgressSubmitOptions {
	if opts.Type == "" {
		opts.Type = "1"
	}
	if opts.Tjzj == "" {
		opts.Tjzj = "1"
	}
	if opts.ClockInDot == "" {
		opts.ClockInDot = fmt.Sprintf("%d", opts.PlayProgress)
	}
	if opts.ClockInRule == "" {
		opts.ClockInRule = "0"
	}
	if opts.TimeLimit == "" {
		opts.TimeLimit = "-1"
	}
	if opts.EventType == "" {
		opts.EventType = "study"
	}
	if opts.CourseType == "" {
		opts.CourseType = "2"
	}
	if opts.PlatformID == "" {
		opts.PlatformID = "13145854983311"
	}
	if opts.Referer == "" {
		opts.Referer = "https://www.ttcdw.cn/p/course/videorevision/v_" + opts.VideoID + "?ddtab=true&itemId=" + opts.ItemID + "&segId=" + opts.SegID + "&projectId=" + opts.SourceID + "&orgId=" + opts.OrgID + "&type=" + opts.Type + "&courseType=" + opts.CourseType
	}
	return opts
}

func ttcdwHTTPClient(cache *TtcdwUserCache) *http.Client {
	tr := &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
	return &http.Client{Transport: tr}
}

func stringifyTtcdw(v interface{}) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64:
		if x == float64(int64(x)) {
			return fmt.Sprintf("%d", int64(x))
		}
		return fmt.Sprintf("%v", x)
	case bool:
		return fmt.Sprintf("%t", x)
	default:
		return fmt.Sprintf("%v", x)
	}
}

func (cache *TtcdwUserCache) TickerSubmitApi(tickerURL, serverDataName, tickerData string, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	if tickerURL == "" {
		return "", errors.New("tickerURL is required")
	}
	if serverDataName == "" {
		serverDataName = "tickerData"
	}
	form := url.Values{}
	form.Set(serverDataName, tickerData)

	tr := &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}
	client := &http.Client{Transport: tr}
	req, err := http.NewRequest("POST", tickerURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Add("User-Agent", utils.DefaultUserAgent)
	req.Header.Add("Accept", "application/json, text/javascript, */*; q=0.01")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
	req.Header.Add("Connection", "keep-alive")
	for _, cookie := range cache.Cookies {
		req.AddCookie(cookie)
	}
	res, err := client.Do(req)
	if err != nil {
		time.Sleep(150 * time.Millisecond)
		return cache.TickerSubmitApi(tickerURL, serverDataName, tickerData, retry-1, err)
	}
	defer res.Body.Close()
	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		return "", err
	}
	utils.CookiesAddNoRepetition(&cache.Cookies, res.Cookies())
	return string(body), nil
}

func pkcs7Padding(src []byte, blockSize int) []byte {
	padding := blockSize - len(src)%blockSize
	padText := bytes.Repeat([]byte{byte(padding)}, padding)
	return append(src, padText...)
}

// DES 加密函数。
func encrypt(message string, key string) (string, error) {
	// DES 只接受 8 字节密钥，原项目也是取前 8 字节。
	keyBytes := []byte(key)
	if len(keyBytes) < 8 {
		return "", errors.New("key must be at least 8 bytes long")
	}
	if len(keyBytes) > 8 {
		keyBytes = keyBytes[:8]
	}

	c, err := des.NewCipher(keyBytes)
	if err != nil {
		return "", err
	}

	// 按 DES 块大小做 PKCS7 填充。
	messageBytes := []byte(message)
	messageBytes = pkcs7Padding(messageBytes, des.BlockSize)

	cipherText := make([]byte, len(messageBytes))
	for i := 0; i < len(messageBytes); i += des.BlockSize {
		c.Encrypt(cipherText[i:i+des.BlockSize], messageBytes[i:i+des.BlockSize])
	}

	return base64.StdEncoding.EncodeToString(cipherText), nil
}

// 数据分组函数
func group(str string, step int) []string {
	var result []string
	for i := 0; i < len(str); i += step {
		end := i + step
		if end > len(str) {
			end = len(str)
		}
		result = append(result, str[i:end])
	}
	return result
}

// 加密数据函数
func EncData(dataStr string) (string, error) {
	// 按照100字符分组
	arr := group(dataStr, 100)
	var rulArr []string

	for _, item := range arr {
		encryptedValue, err := encrypt(item, "MK49ICOURSES1102")
		if err != nil {
			return "", err
		}
		rulArr = append(rulArr, encryptedValue)
	}

	b, err := json.Marshal(rulArr)
	if err != nil {
		return "", err
	}
	return string(b), nil
}
