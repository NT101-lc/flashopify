interface PlaceholderPageProps {
  title: string;
}

/** Temporary page body until the feature is implemented. */
export function PlaceholderPage({ title }: PlaceholderPageProps) {
  return (
    <section className="py-10">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="mt-2 text-muted-foreground">Coming soon.</p>
    </section>
  );
}
