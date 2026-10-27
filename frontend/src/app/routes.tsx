import type { RouteObject } from 'react-router';

import { AccountPage } from '@/features/account/pages/AccountPage';
import { AdminFlashSalesPage } from '@/features/admin/pages/AdminFlashSalesPage';
import { AdminHomePage } from '@/features/admin/pages/AdminHomePage';
import { AdminOrdersPage } from '@/features/admin/pages/AdminOrdersPage';
import { AdminProductsPage } from '@/features/admin/pages/AdminProductsPage';
import { CartPage } from '@/features/cart/pages/CartPage';
import { HomePage } from '@/features/catalog/pages/HomePage';
import { ProductDetailPage } from '@/features/catalog/pages/ProductDetailPage';
import { ProductListPage } from '@/features/catalog/pages/ProductListPage';
import { CheckoutPage } from '@/features/checkout/pages/CheckoutPage';
import { FlashSalePage } from '@/features/flash-sale/pages/FlashSalePage';
import { NotFoundPage } from '@/shared/ui/NotFoundPage';

import { AdminLayout } from './layouts/AdminLayout';
import { StorefrontLayout } from './layouts/StorefrontLayout';

export const routes: RouteObject[] = [
  {
    element: <StorefrontLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'products', element: <ProductListPage /> },
      { path: 'products/:slug', element: <ProductDetailPage /> },
      { path: 'flash-sales', element: <FlashSalePage /> },
      { path: 'cart', element: <CartPage /> },
      { path: 'checkout', element: <CheckoutPage /> },
      { path: 'account', element: <AccountPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
  {
    path: 'admin',
    element: <AdminLayout />,
    children: [
      { index: true, element: <AdminHomePage /> },
      { path: 'orders', element: <AdminOrdersPage /> },
      { path: 'products', element: <AdminProductsPage /> },
      { path: 'flash-sales', element: <AdminFlashSalesPage /> },
    ],
  },
];
