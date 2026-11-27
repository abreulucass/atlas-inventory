import { renderToReadableStream } from "react-dom/server";

/** Renders async Server Components (Suspense included) to HTML and puts it in document.body. */
export async function renderServer(element: React.ReactElement) {
  const stream = await renderToReadableStream(element);
  await stream.allReady;
  document.body.innerHTML = await new Response(stream).text();
}
