# Orchestration On-Device Test Plan

Uses 3 mock skills (get-date, get-weather, format-report) designed to be chained together.

| # | Scenario | Skills | Test Prompt | Pass Pattern | Timeout |
|---|----------|--------|-------------|-------------|---------|
| 1 | three-skill-chain | get-date,get-weather,format-report | Get today's date, check the weather in Tokyo, then format both results into a report | Called JS script | 240 |
