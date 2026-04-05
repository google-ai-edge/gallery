async function ai_edge_gallery_get_result(inputJson) {
  const input = JSON.parse(inputJson);
  const city = input.city || "Unknown City";
  const temps = [18, 22, 25, 28, 15, 20, 30];
  const conditions = ["Sunny", "Partly Cloudy", "Clear", "Overcast", "Light Rain"];
  const temp = temps[Math.floor(Math.random() * temps.length)];
  const condition = conditions[Math.floor(Math.random() * conditions.length)];
  return JSON.stringify({
    result: `Weather in ${city}: ${condition}, ${temp}°C. Humidity: 65%. Wind: 12 km/h.`
  });
}
