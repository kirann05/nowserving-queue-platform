/**
 * The entry point Vite loads from index.html. Mounts React into #root and
 * wraps the app in its two global providers:
 *   BrowserRouter — URL <-> component routing
 *   AuthProvider  — who is logged in (see auth/AuthContext.tsx)
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthContext';
import './index.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
