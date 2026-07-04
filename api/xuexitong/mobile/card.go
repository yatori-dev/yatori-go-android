package mobile

import (
	"io/ioutil"
	"net/http"
	"net/url"
	"strconv"
)

// PageMobileChapterCardApi fetches raw HTML for a chapter's task cards.
// Returns card HTML that contains job/task detail (jobid, objectid, dtoken, etc.).
func (c *XxtClient) PageMobileChapterCardApi(classId, courseId, knowledgeId, cardIndex, cpi int, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	params := url.Values{}
	params.Set("clazzid", strconv.Itoa(classId))
	params.Set("courseid", strconv.Itoa(courseId))
	params.Set("knowledgeid", strconv.Itoa(knowledgeId))
	params.Set("num", strconv.Itoa(cardIndex))
	params.Set("isPhone", "1")
	params.Set("control", "true")
	params.Set("cpi", strconv.Itoa(cpi))

	req, err := http.NewRequest("GET", "https://mooc1-api.chaoxing.com/knowledge/cards?"+params.Encode(), nil)
	if err != nil {
		return c.PageMobileChapterCardApi(classId, courseId, knowledgeId, cardIndex, cpi, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Host", "mooc1-api.chaoxing.com")
	resp, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return c.PageMobileChapterCardApi(classId, courseId, knowledgeId, cardIndex, cpi, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.PageMobileChapterCardApi(classId, courseId, knowledgeId, cardIndex, cpi, retry-1, err)
	}
	return string(body), nil
}
