import { House, Package, ShoppingCart, Zap } from 'lucide-react';
import { Link, NavLink, Outlet } from 'react-router';

import { cn } from '@/shared/lib/utils';

const navItems = [
  { to: '/admin', label: 'Home', icon: House, end: true },
  { to: '/admin/orders', label: 'Orders', icon: ShoppingCart, end: false },
  { to: '/admin/products', label: 'Products', icon: Package, end: false },
  { to: '/admin/flash-sales', label: 'Flash sales', icon: Zap, end: false },
];

/** Admin shell modelled on Shopify admin: dark top bar, left sidebar, grey content area. */
export function AdminLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-muted">
      <header className="flex h-14 items-center bg-neutral-900 px-4 text-white">
        <Link to="/admin" className="font-semibold">
          Flashopify admin
        </Link>
      </header>
      <div className="flex flex-1">
        <nav aria-label="Admin" className="w-60 shrink-0 p-3">
          <ul className="space-y-1">
            {navItems.map(({ to, label, icon: Icon, end }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-2 rounded-md px-3 py-1.5 text-sm hover:bg-background/70',
                      isActive && 'bg-background font-semibold',
                    )
                  }
                >
                  <Icon className="size-4" />
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
