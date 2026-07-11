import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../api-base-url';
import { SystemInfo, SystemService } from './system-service';

describe('SystemService', () => {
  it('loads system information from configured API base URL', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE_URL, useValue: '/backend' }]
    });
    const service = TestBed.inject(SystemService);
    const http = TestBed.inject(HttpTestingController);
    let actual: SystemInfo | undefined;

    service.getInfo().subscribe(value => actual = value);

    const request = http.expectOne('/backend/api/system/info');
    expect(request.request.method).toBe('GET');
    request.flush({ name: 'Maven Project Helper', version: 'Development' });
    expect(actual).toEqual({ name: 'Maven Project Helper', version: 'Development' });
    http.verify();
  });
});
