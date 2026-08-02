import { renderHook } from "@testing-library/react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CopyableId, EmptyState, Pagination, rowActivation, useRaceGuard } from "./ui";

describe("useRaceGuard", () => {
  it("treats the first ticket as current until a newer one is started", () => {
    const { result } = renderHook(() => useRaceGuard());
    const ticket = result.current.startFetch();
    expect(result.current.isCurrent(ticket)).toBe(true);
  });

  it("invalidates an older ticket once a newer fetch has started", () => {
    // This is the exact shape of the bug it exists to prevent: two requests fire (an
    // auto-refresh tick and a manual click), and the one that resolves *first* is not
    // necessarily the one that was sent *last*.
    const { result } = renderHook(() => useRaceGuard());
    const older = result.current.startFetch();
    const newer = result.current.startFetch();

    expect(result.current.isCurrent(older)).toBe(false);
    expect(result.current.isCurrent(newer)).toBe(true);
  });

  it("keeps ticket identity stable across re-renders of the same hook instance", () => {
    const { result, rerender } = renderHook(() => useRaceGuard());
    const ticket = result.current.startFetch();
    rerender();
    // startFetch/isCurrent are useCallback-memoized with no deps; a stale closure here would
    // silently break every screen that lists them in a useCallback dependency array.
    expect(result.current.isCurrent(ticket)).toBe(true);
  });
});

describe("rowActivation", () => {
  it("activates on click", () => {
    const onActivate = vi.fn();
    const props = rowActivation(onActivate);
    render(
      <table>
        <tbody>
          <tr {...props}>
            <td>row</td>
          </tr>
        </tbody>
      </table>
    );
    fireEvent.click(screen.getByRole("button"));
    expect(onActivate).toHaveBeenCalledTimes(1);
  });

  it("activates on Enter and Space, the two keys a real button responds to", () => {
    const onActivate = vi.fn();
    const props = rowActivation(onActivate);
    render(
      <table>
        <tbody>
          <tr {...props}>
            <td>row</td>
          </tr>
        </tbody>
      </table>
    );
    const row = screen.getByRole("button");
    fireEvent.keyDown(row, { key: "Enter" });
    fireEvent.keyDown(row, { key: " " });
    expect(onActivate).toHaveBeenCalledTimes(2);
  });

  it("ignores an unrelated key", () => {
    const onActivate = vi.fn();
    const props = rowActivation(onActivate);
    render(
      <table>
        <tbody>
          <tr {...props}>
            <td>row</td>
          </tr>
        </tbody>
      </table>
    );
    fireEvent.keyDown(screen.getByRole("button"), { key: "Tab" });
    expect(onActivate).not.toHaveBeenCalled();
  });

  it("is reachable by keyboard: tabIndex 0 and an explicit button role", () => {
    const props = rowActivation(() => {});
    render(
      <table>
        <tbody>
          <tr {...props}>
            <td>row</td>
          </tr>
        </tbody>
      </table>
    );
    const row = screen.getByRole("button");
    expect(row).toHaveAttribute("tabindex", "0");
  });
});

describe("CopyableId", () => {
  it("shows the truncated id by default and the full id when asked", () => {
    const id = "4463f7f1-cd6d-4f7e-a2b9-6da40c78639e";
    const { rerender } = render(<CopyableId id={id} />);
    expect(screen.getByText("4463f7f1…639e")).toBeInTheDocument();

    rerender(<CopyableId id={id} full />);
    expect(screen.getByText(id)).toBeInTheDocument();
  });

  it("copies the full id to the clipboard, not the shortened display text", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const id = "4463f7f1-cd6d-4f7e-a2b9-6da40c78639e";
    render(<CopyableId id={id} />);

    fireEvent.click(screen.getByRole("button"));
    expect(writeText).toHaveBeenCalledWith(id);
  });

  it("does not let a click bubble to an ancestor row's own click handler", () => {
    // CopyableId lives inside clickable rows throughout the app; copying an id must not also
    // open the row it sits in.
    const onRowClick = vi.fn();
    render(
      <table>
        <tbody>
          <tr onClick={onRowClick}>
            <td>
              <CopyableId id="abc123" />
            </td>
          </tr>
        </tbody>
      </table>
    );
    fireEvent.click(screen.getByRole("button"));
    expect(onRowClick).not.toHaveBeenCalled();
  });
});

describe("EmptyState", () => {
  it("renders the title and detail passed to it", () => {
    render(<EmptyState title="No payouts yet" detail="Nothing has been batched so far." />);
    expect(screen.getByText("No payouts yet")).toBeInTheDocument();
    expect(screen.getByText("Nothing has been batched so far.")).toBeInTheDocument();
  });
});

describe("Pagination", () => {
  it("shows the correct 1-based range for a middle page", () => {
    render(<Pagination page={1} size={20} totalItems={45} totalPages={3} onPage={() => {}} />);
    expect(screen.getByText("21–40 of 45")).toBeInTheDocument();
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
  });

  it("disables Previous on the first page and Next on the last", () => {
    const { rerender } = render(
      <Pagination page={0} size={20} totalItems={45} totalPages={3} onPage={() => {}} />
    );
    expect(screen.getByText("Previous")).toBeDisabled();
    expect(screen.getByText("Next")).not.toBeDisabled();

    rerender(<Pagination page={2} size={20} totalItems={45} totalPages={3} onPage={() => {}} />);
    expect(screen.getByText("Previous")).not.toBeDisabled();
    expect(screen.getByText("Next")).toBeDisabled();
  });

  it("reports nothing to show rather than a nonsensical 1-0 of 0", () => {
    render(<Pagination page={0} size={20} totalItems={0} totalPages={0} onPage={() => {}} />);
    expect(screen.getByText("Nothing to show")).toBeInTheDocument();
  });

  it("calls onPage with the target page when Next is clicked", () => {
    const onPage = vi.fn();
    render(<Pagination page={0} size={20} totalItems={45} totalPages={3} onPage={onPage} />);
    fireEvent.click(screen.getByText("Next"));
    expect(onPage).toHaveBeenCalledWith(1);
  });
});
