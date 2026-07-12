import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../api-base-url';
import { FileSystemService, FolderResponse } from './file-system-service';

describe('FileSystemService', () => {
  let service: FileSystemService;
  let http: HttpTestingController;
  const response: FolderResponse = {
    path: '/projects', parentPath: '/', remembered: true, maxScanDepth: 4,
    nexusIqUrl: 'https://iq.example.org', children: [{ name: 'sample', path: '/projects/sample' }]
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: 'http://test-host' }
      ]
    });
    service = TestBed.inject(FileSystemService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the current folder', () => {
    let actual: FolderResponse | undefined;
    service.current().subscribe(value => actual = value);

    const request = http.expectOne('http://test-host/api/filesystem/current');
    expect(request.request.method).toBe('GET');
    request.flush(response);
    expect(actual).toEqual(response);
  });

  it('loads child folders with an encoded path parameter', () => {
    service.folders('/projects/with space').subscribe();

    const request = http.expectOne(req => req.url === 'http://test-host/api/filesystem/folders');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('path')).toBe('/projects/with space');
    request.flush(response);
  });

  it('saves every folder and Nexus IQ setting', () => {
    service.saveBase('/projects', 6, 'https://iq.example.org', 'api-user', 'secret', 'pre-', '-suffix').subscribe();

    const request = http.expectOne('http://test-host/api/filesystem/base');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      path: '/projects', maxScanDepth: 6, nexusIqUrl: 'https://iq.example.org',
      nexusIqUser: 'api-user', nexusIqPass: 'secret', nexusIqAppIdPrefix: 'pre-', nexusIqAppIdSuffix: '-suffix'
    });
    request.flush(response);
  });

  it('omits an unavailable secret so the backend can preserve the stored value', () => {
    service.saveBase('/projects', 6, 'https://iq.example.org', 'api-user', undefined, 'pre-', '-suffix').subscribe();

    const request = http.expectOne('http://test-host/api/filesystem/base');
    expect(Object.prototype.hasOwnProperty.call(request.request.body, 'nexusIqPass')).toBe(false);
    request.flush(response);
  });
});
