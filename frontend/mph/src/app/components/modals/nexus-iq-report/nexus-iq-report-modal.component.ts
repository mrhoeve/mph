import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NexusIqReportViolation, NexusIqScanResponse } from '../../../services/maven-project-service';

@Component({
  selector: 'app-nexus-iq-report-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './nexus-iq-report-modal.component.html',
  styleUrl: './nexus-iq-report-modal.component.css'
})
export class NexusIqReportModalComponent {
  @Input({ required: true }) result!: NexusIqScanResponse;
  @Input({ required: true }) projectName!: string;
  @Output() close = new EventEmitter<void>();

  severity(violation: NexusIqReportViolation): string {
    if (violation.threatLevel >= 8) return 'critical';
    if (violation.threatLevel >= 4) return 'severe';
    if (violation.threatLevel >= 2) return 'moderate';
    return 'low';
  }
}
