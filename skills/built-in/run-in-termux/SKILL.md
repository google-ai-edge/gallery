---
name: run-in-termux
description: Run shell commands or code (Python, Node.js, bash, etc.) in the Termux terminal on the device.
---

# Run code in Termux

This skill executes shell commands and code snippets directly in Termux on the
device. It supports any language or tool installed in Termux (bash, Python 3,
Node.js, Ruby, etc.).

## Examples

* "Run a Python script that prints the Fibonacci sequence"
* "What is my Python version?"
* "Run: echo hello world"
* "Execute this bash script: ..."
* "Install numpy in Termux"

## Instructions

When the user asks to run code or a command in Termux, call the
`run_termux_command` tool with the following parameters:

- command: The exact shell command or code to run. For multi-line scripts,
  embed newlines with `\n`. For single-language snippets, prefer the inline
  form, e.g. `python3 -c "..."` or `node -e "..."`.
- showTerminal: `true` (default) so the user can see the output in the Termux
  app. Set to `false` only when the command should run silently.

After calling the tool, inform the user that the command has been sent to
Termux. If the user asks for the output, ask them to check the Termux app and
share the result.
