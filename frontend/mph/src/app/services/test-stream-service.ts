import {inject, Injectable, NgZone} from '@angular/core';
import { Observable } from 'rxjs';
import {API_BASE_URL} from '../api-base-url';

@Injectable({
  providedIn: 'root',
})
export class TestStreamService {
  private readonly ngZone = inject(NgZone);
  private readonly apiBaseUrl = inject(API_BASE_URL);

  currentTime$(): Observable<string> {
    return new Observable<string>((observer) => {
      const eventSource = new EventSource(`${this.apiBaseUrl}/api/test`);

      eventSource.onmessage = (event) => {
        this.ngZone.run(() => {
          observer.next(event.data);
        });
      };

      eventSource.onerror = (error) => {
        this.ngZone.run(() => {
          observer.error(error);
        });

        eventSource.close();
      };

      return () => {
        eventSource.close();
      };
    });
  }
}
