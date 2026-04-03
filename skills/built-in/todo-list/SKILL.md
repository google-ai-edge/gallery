---
name: todo-list
description: A persistent to-do list stored on-device. Add, complete, delete, and view tasks. Use when the user wants to manage tasks, create a to-do list, check off items, or clear completed tasks.
---

# To-Do List

A simple, private to-do list stored entirely on your device. No cloud, no account needed.

## Examples

* "Add 'Buy groceries' to my to-do list"
* "Show my to-do list"
* "Mark 'Buy groceries' as done"
* "Delete the milk task"
* "Clear all completed tasks"
* "What do I still need to do?"

## Actions

### Add a task
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"add"`
  - `text`: String — the task description

### List tasks
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"list"`
  - `filter`: String (Optional) — `"all"` (default), `"pending"`, or `"completed"`

### Complete a task
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"complete"`
  - `id`: Number — the task ID shown in the list

### Uncomplete a task (reopen)
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"uncomplete"`
  - `id`: Number — the task ID

### Delete a task
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"delete"`
  - `id`: Number — the task ID

### Clear completed tasks
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"clear_completed"`

### Wipe all tasks
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"wipe"`

## Rules

- Always call `list` after `add`, `complete`, `delete`, or `clear_completed` to show the user the updated list.
- When identifying a task by name (e.g. "mark 'Buy milk' as done"), first call `list` to find the ID, then call `complete` with that ID.
- All data is stored locally and privately on the device.
