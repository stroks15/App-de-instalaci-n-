import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'KioskGrid Backend',
  description: 'Backend API para KioskGrid - Gestión de dispositivos, tareas y pagos',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="es">
      <body>{children}</body>
    </html>
  );
}