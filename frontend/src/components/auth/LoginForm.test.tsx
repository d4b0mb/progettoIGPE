import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import { renderWithIntl } from "@/test/render";
import { LoginForm } from "./LoginForm";

const replace = vi.fn();
const signIn = vi.fn();

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("@/api/session", () => ({ useSession: () => ({ signIn }) }));

beforeEach(() => {
  replace.mockReset();
  signIn.mockReset();
});

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Email"), email);
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Sign in" }));
}

describe("LoginForm", () => {
  it("signs in with what was typed and goes to the home screen", async () => {
    signIn.mockResolvedValueOnce(undefined);
    renderWithIntl(<LoginForm />);
    await fillAndSubmit("marco@example.com", "correct-horse");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    expect(signIn).toHaveBeenCalledWith("marco@example.com", "correct-horse");
  });

  it("shows a clear message for a wrong password and stays on the page", async () => {
    signIn.mockRejectedValueOnce(new ApiError(401, "Invalid email or password"));
    renderWithIntl(<LoginForm />);
    await fillAndSubmit("marco@example.com", "wrong");
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password");
    expect(replace).not.toHaveBeenCalled();
    // The button is usable again so the person can retry.
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("falls back to a generic message for an unexpected failure", async () => {
    signIn.mockRejectedValueOnce(new Error("network down"));
    renderWithIntl(<LoginForm />);
    await fillAndSubmit("marco@example.com", "whatever");
    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong");
  });

  it("links to restaurant sign-up", () => {
    renderWithIntl(<LoginForm />);
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/signup");
  });
});
