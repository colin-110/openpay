import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
import "@testing-library/jest-dom/vitest";

// Without this, each render() in a new test appends to the same jsdom document instead of
// starting fresh — the last suite's rendered output stays in the DOM and getByText/getByRole
// starts matching multiple elements across unrelated tests.
afterEach(() => {
  cleanup();
});
