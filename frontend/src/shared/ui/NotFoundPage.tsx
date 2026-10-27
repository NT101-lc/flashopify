import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <section className="py-10">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <Link to="/" className="mt-2 inline-block text-primary underline-offset-4 hover:underline">
        Back to the store
      </Link>
    </section>
  );
}
