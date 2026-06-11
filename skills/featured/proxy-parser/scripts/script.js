const PROXY_KEY = 'YOUR KEY HERE';
const PROXY_SERVER_URL = 'https://corsproxy.io/?&key=' + PROXY_KEY + '&url=';
const OUTPUT_ELEMENT = document.getElementById('output');

window['ai_edge_gallery_get_result'] = async (data) => {
  try {
    const jsonData = JSON.parse(data);
    return JSON.stringify(await fetchRemoteUrlData(jsonData['url'], jsonData['domSelector']));
  } catch (e) {
    console.error(e);
    return JSON.stringify({error: `Failed to fetch remote URL data: ${e.message}`});
  }
};


async function fetchRemoteUrlData(url, extractionCriteria = 'p') {
  try {
    const response = await fetch(PROXY_SERVER_URL + encodeURIComponent(url));
    if (!response.ok) {
      console.error(`Failed to fetch data from ${url}: ${response.statusText}`);
    }

    const TEXT = await response.text();
    const HTML_DOM = new DOMParser().parseFromString(TEXT, 'text/html');
    const EXTRACTION = HTML_DOM.querySelectorAll(extractionCriteria);

    let result = '';
    for (let i = 0; i < EXTRACTION.length; i++) {
      result += EXTRACTION[i].innerText + '\n';
    }

    OUTPUT_ELEMENT.innerText = result;
    return result;
  } catch (e) {
    console.error(e);
  }
}
