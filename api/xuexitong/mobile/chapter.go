package mobile

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

const chapterFields = "id,bbsid,classscore,isstart,allowdownload,chatid,name,state,isfiled,visiblescore,hideclazz,begindate,forbidintoclazz,coursesetting.fields(id,courseid,hiddencoursecover,coursefacecheck),course.fields(id,belongschoolid,name,infocontent,objectid,app,bulletformat,mappingcourseid,imageurl,teacherfactor,jobcount,knowledge.fields(id,name,indexOrder,parentnodeid,status,isReview,layer,label,jobcount,begintime,endtime,attachment.fields(id,type,objectid,extension)))"

func ChapterURL(cpi, key int) string {
	return ChapterGasURL(cpi, key)
}

func ChapterGasURL(cpi, key int) string {
	params := url.Values{}
	params.Set("id", strconv.Itoa(key))
	params.Set("personid", strconv.Itoa(cpi))
	params.Set("fields", chapterFields)
	params.Set("view", "json")
	return fmt.Sprintf("https://mooc1-api.chaoxing.com/gas/clazz?%s", params.Encode())
}

func ChapterInfoURL(cpi, key int) string {
	params := url.Values{}
	params.Set("id", strconv.Itoa(key))
	params.Set("personid", strconv.Itoa(cpi))
	params.Set("fields", chapterFields)
	params.Set("view", "json")
	return fmt.Sprintf("https://mooc1-api.chaoxing.com/clazz/info?%s", params.Encode())
}

// PullChapterApi fetches the chapter/knowledge tree for a course class.
// cpi = personId (channel.Cpi), key = classId (channel.Key as int).
func (c *XxtClient) PullChapterApi(cpi, key int, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	primaryRaw, primaryErr := c.pullChapterURL(ChapterGasURL(cpi, key))
	if primaryErr == nil && looksLikeUsableChapterJSON(primaryRaw) {
		return primaryRaw, nil
	}

	fallbackRaw, fallbackErr := c.pullChapterURL(ChapterInfoURL(cpi, key))
	if fallbackErr == nil && looksLikeUsableChapterJSON(fallbackRaw) {
		return fallbackRaw, nil
	}

	if fallbackRaw != "" {
		return fallbackRaw, nil
	}
	if primaryRaw != "" {
		return primaryRaw, nil
	}
	return c.PullChapterApi(cpi, key, retry-1, fmt.Errorf("gas/clazz: %v; clazz/info: %v", primaryErr, fallbackErr))
}

func (c *XxtClient) pullChapterURL(rawURL string) (string, error) {
	req, err := http.NewRequest("GET", rawURL, nil)
	if err != nil {
		return "", err
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Accept-Language", "zh_CN")
	req.Header.Add("Host", "mooc1-api.chaoxing.com")
	req.Header.Add("Connection", "Keep-Alive")
	resp, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	raw := string(body)
	if resp.StatusCode != http.StatusOK {
		return raw, fmt.Errorf("http %d", resp.StatusCode)
	}
	return raw, nil
}

func looksLikeJSON(raw string) bool {
	trimmed := strings.TrimSpace(raw)
	return strings.HasPrefix(trimmed, "{") || strings.HasPrefix(trimmed, "[")
}

func looksLikeUsableChapterJSON(raw string) bool {
	if !looksLikeJSON(raw) {
		return false
	}
	var resp struct {
		Data []struct {
			Course struct {
				Data []json.RawMessage `json:"data"`
			} `json:"course"`
		} `json:"data"`
		Course struct {
			Data []json.RawMessage `json:"data"`
		} `json:"course"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return false
	}
	if len(resp.Course.Data) > 0 {
		return true
	}
	return len(resp.Data) > 0 && len(resp.Data[0].Course.Data) > 0
}
