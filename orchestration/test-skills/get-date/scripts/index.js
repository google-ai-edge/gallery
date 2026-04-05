async function ai_edge_gallery_get_result(inputJson) {
  const now = new Date();
  const dateStr = now.toLocaleDateString('en-US', {
    weekday: 'long', year: 'numeric', month: 'long', day: 'numeric'
  });
  const timeStr = now.toLocaleTimeString('en-US');
  return JSON.stringify({
    result: `Today is ${dateStr}. The current time is ${timeStr}.`
  });
}
