package mobile

import (
	"io/ioutil"
	"net/http"
)

// CourseListApi fetches the raw course list JSON.
func (c *XxtClient) CourseListApi(retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	req, err := http.NewRequest("GET", "https://mooc1-api.chaoxing.com/mycourse/backclazzdata", nil)
	if err != nil {
		return c.CourseListApi(retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	resp, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return c.CourseListApi(retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.CourseListApi(retry-1, err)
	}
	return string(body), nil
}
