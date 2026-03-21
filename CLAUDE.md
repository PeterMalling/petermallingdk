# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a single-file web project — no build tools, no package manager, no dependencies. Everything runs directly in the browser.

## Architecture

`tictactoe.html` is a self-contained Tic Tac Toe game with all HTML, CSS, and JavaScript in one file:

- **Game logic**: `board` (9-element array), `current` (active player), `gameOver` flag
- **AI**: Minimax algorithm in `minimax()` / `checkWinnerOn()` — unbeatable when playing as O
- **Modes**: `vsComputer` toggle switches between human vs human and human vs AI
- **Rendering**: `renderBoard()` rebuilds the DOM from scratch on every state change

## Git & GitHub

- Remote: https://github.com/PeterMalling/petermallingdk
- Always commit and push after making changes
- Use descriptive commit messages explaining *why*, not just *what*
