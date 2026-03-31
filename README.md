# Local-LLM-commit-assist-CLI

A CLI version of Local-LLM-commit-assist that generates a commit message from a local LLM, commits the current repository, and can optionally push the current branch.

This project intentionally does **not** implement pull request creation.

## Features

- Runs from the current project folder
- Uses a local LLM through an OpenAI-compatible `/chat/completions` API
- Stages all files by default before generating the commit
- Commits automatically after generating the message
- Optionally pushes automatically
- Stores simple user config for defaults

## Requirements

- Java 21+
- Git installed and available on `PATH`
- A local LLM server with an OpenAI-compatible API

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
