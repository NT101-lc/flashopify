import { CircleUser, ShoppingBag } from 'lucide-react';
import { Link, NavLink, Outlet } from 'react-router';

import { cn } from '@/shared/lib/utils';

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  cn('text-sm underline-offset-4 hover:underline', isActive && 'underline');

/** Storefront shell modelled on Shopify's Dawn theme: announcement bar, header, footer. */
export function StorefrontLayout() {
  return (
    <div className="flex min-h-screen flex-col">
      <div className="bg-foreground py-2 text-center text-xs text-background">
        Flash sales every day
      </div>
      <header className="border-b">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4">
          <nav aria-label="Main" className="flex gap-6">
            <NavLink to="/products" className={navLinkClass}>
              Catalog
            </NavLink>
            <NavLink to="/flash-sales" className={navLinkClass}>
              Flash sale
            </NavLink>
          </nav>
          <Link to="/" className="text-xl font-semibold tracking-tight">
            Flashopify
          </Link>
          <div className="flex gap-4">
            <Link to="/account" aria-label="Account">
              <CircleUser className="size-5" />
            </Link>
            <Link to="/cart" aria-label="Cart">
              <ShoppingBag className="size-5" />
            </Link>
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4">
        <Outlet />
      </main>
      <footer className="border-t py-8 text-center text-xs text-muted-foreground">
        © Flashopify
      </footer>
    </div>
  );
}
