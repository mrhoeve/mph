import { InjectionToken, isDevMode } from '@angular/core';

export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => (isDevMode() ? 'http://localhost:8080' : ''),
});
