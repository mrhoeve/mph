import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../api-base-url';

export interface SystemInfo {
  name: string;
  version: string;
}

@Injectable({
  providedIn: 'root',
})
export class SystemService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);

  getInfo(): Observable<SystemInfo> {
    return this.http.get<SystemInfo>(`${this.apiBaseUrl}/api/system/info`);
  }
}
