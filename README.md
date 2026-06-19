# Local-LLM-commit-assist-CLI

A CLI version of Local-LLM-commit-assist that generates a commit message from a local LLM, commits the current repository, and can optionally push the current branch.

This project intentionally does **not** implement pull request creation.

## Features

- Runs from the current project folder
- Uses a local LLM through an OpenAI-compatible `/chat/completions` API
- Automatically detects the model's context window via the provider's `/models` endpoint
- Compacts large diffs to fit within the available token budget
- Retries with a progressively smaller input budget on context overflow errors
- Stages all files by default before generating the commit
- Commits automatically after generating the message
- Optionally pushes automatically
- Stores simple user config for defaults

## Requirements

- Java 21+
- Git installed and available on `PATH`
- A local LLM server with an OpenAI-compatible API (LM Studio, Ollama, llama.cpp, etc.)

## Build

```shell
./gradlew installDist
```

The generated CLI script will be:

```shell
build/install/llm-commit/bin/llm-commit
```

You can symlink that script somewhere on your `PATH` if you want a global `llm-commit` command.

Example:

```shell
mkdir -p ~/.local/bin
ln -s "$(pwd)/build/install/llm-commit/bin/llm-commit" ~/.local/bin/llm-commit
```

Your shell must include `~/.local/bin` in `PATH` for `llm-commit` to work from any folder. You can check with:

```shell
echo $PATH
```

If `~/.local/bin` is not listed there, symlink `llm-commit` into a directory that already is, such as `/usr/local/bin`.

## Usage

From inside a git repository:

```shell
llm-commit
```

Test the configured local LLM without creating a commit:

```shell
llm-commit --test
```

Override behavior for one run:

```shell
llm-commit --push
llm-commit --no-push
llm-commit --add-all
llm-commit --no-add-all
```

## Configuration

Show current config:

```shell
llm-commit config show
```

Set the LLM endpoint:

```shell
llm-commit config set llm-address http://localhost:1234/v1
```

Set the model name:

```shell
llm-commit config set model-name qwen2.5-coder-32b-instruct
```

Enable automatic push:

```shell
llm-commit config set always-push true
```

Disable automatic stage-all:

```shell
llm-commit config set always-add-all false
```

The config file is stored at:

```text
~/.config/llm-commit/config.properties
```

## Context window handling

When generating a commit message, `llm-commit` automatically queries the provider's `/models` endpoint to discover the model's context window size. This result is cached for the lifetime of the process so repeated calls don't re-probe the server.

If the model's context window cannot be determined (e.g. the endpoint is unavailable or returns no size information), a conservative default of 8 192 tokens is used.

Large diffs are compacted before being sent. When a diff exceeds the available token budget, the compactor:

1. Splits the diff into per-file sections.
2. Truncates individual sections that are too large (preserving the file header and both the start and end of the hunk).
3. Drops trailing sections if the combined result still exceeds the budget.
4. Prepends a notice describing how many files were in the original diff and how many are included after compaction.

If the provider still returns a context-overflow error (HTTP 400, 413, or 422 with a recognized error body), `llm-commit` retries up to three times using progressively smaller input budgets (100 %, 72 %, and 50 % of the available window). On each overflow the effective context window is halved and the reduced size is persisted in the cache so future calls use the corrected value.

OpenAI o-series models (`o1`, `o3`, etc.) require `max_completion_tokens` instead of `max_tokens`. `llm-commit` detects these models automatically and uses the correct parameter.
