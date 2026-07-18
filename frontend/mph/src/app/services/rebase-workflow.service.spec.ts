import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../api-base-url';
import { RebaseProgress, RebaseProgressStatus, RebaseWorkflowService } from './rebase-workflow.service';

class TestEventSource {
  static instances: TestEventSource[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;

  constructor(readonly url: string) { TestEventSource.instances.push(this); }
  close(): void { this.closed = true; }
}

describe('RebaseWorkflowService', () => {
  let service: RebaseWorkflowService;
  let http: HttpTestingController;
  const originalEventSource = globalThis.EventSource;

  beforeEach(() => {
    TestEventSource.instances = [];
    globalThis.EventSource = TestEventSource as unknown as typeof EventSource;
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE_URL, useValue: '/backend' }]
    });
    service = TestBed.inject(RebaseWorkflowService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    globalThis.EventSource = originalEventSource;
  });

  it('starts selected repositories', () => {
    service.start(['/one/pom.xml']).subscribe(response => expect(response.prefix).toBe('TEST-'));
    const request = http.expectOne('/backend/api/rebase-develop/start');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ rootProjectPaths: ['/one/pom.xml'] });
    request.flush({ prefix: 'TEST-', repositories: [] });
  });

  it('maps progress events and closes the event source', () => {
    const received: RebaseProgress[] = [];
    const subscription = service.events().subscribe(event => received.push(event));
    const source = TestEventSource.instances[0];
    const event = {
      projectPath: null, artifactId: null, repositoryPath: null,
      status: RebaseProgressStatus.COMPLETED, message: 'done', recoveryHint: null,
      stashPreserved: false, overall: true, alignmentSkipped: false
    };

    source.onmessage?.(new MessageEvent('message', { data: JSON.stringify(event) }));

    expect(source.url).toBe('/backend/api/rebase-develop/events');
    expect(received).toEqual([event]);
    subscription.unsubscribe();
    expect(source.closed).toBe(true);
  });
});
