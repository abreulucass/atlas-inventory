import { defineConfig, devices } from "@playwright/test";

const PORT = 3000;

export default defineConfig({
  testDir: "./e2e",
  reporter: [["list"], ["html", { open: "never" }]],
  forbidOnly: !!process.env.CI,
  retries: 0,
  use: { baseURL: `http://localhost:${PORT}`, trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  // Production build against the real backend; start it first (see README).
  webServer: {
    command: "npm run build && npm run start",
    url: `http://localhost:${PORT}/entrar`,
    env: { ATLAS_API_URL: process.env.ATLAS_API_URL ?? "http://localhost:8080" },
    reuseExistingServer: !process.env.CI,
    timeout: 240_000,
  },
});
