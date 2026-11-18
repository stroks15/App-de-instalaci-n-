export default function Home() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-950 px-6 py-16 text-slate-100">
      <section className="w-full max-w-2xl rounded-2xl border border-slate-800 bg-slate-900 p-8 shadow-2xl">
        <p className="mb-3 text-sm font-semibold uppercase tracking-[0.2em] text-blue-400">
          Dexterh4ck
        </p>
        <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">KioskGrid Backend</h1>
        <p className="mt-4 leading-7 text-slate-300">
          Servicio API para administración de dispositivos, tareas y servicios KioskGrid.
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          <a
            className="rounded-lg bg-blue-600 px-4 py-2 font-medium text-white transition hover:bg-blue-500"
            href="/api/health"
          >
            Estado del servicio
          </a>
          <span className="rounded-lg border border-slate-700 px-4 py-2 text-sm text-slate-300">
            API protegida · HTTPS
          </span>
        </div>
      </section>
    </main>
  );
}
