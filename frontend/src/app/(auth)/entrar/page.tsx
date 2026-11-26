import { Suspense } from "react";

import { LoginForm } from "@/components/login-form";

async function LoginWithNext({
  searchParams,
}: {
  searchParams: Promise<{ next?: string | string[] }>;
}) {
  const { next } = await searchParams;
  return <LoginForm next={typeof next === "string" ? next : undefined} />;
}

export default function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string | string[] }>;
}) {
  return (
    <Suspense>
      <LoginWithNext searchParams={searchParams} />
    </Suspense>
  );
}
