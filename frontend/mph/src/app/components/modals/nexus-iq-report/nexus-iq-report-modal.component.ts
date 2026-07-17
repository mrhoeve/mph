import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
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
  @Output() dismissed = new EventEmitter<void>();

  expandedItems = signal<Set<number>>(new Set());

  severity(violation: NexusIqReportViolation): string {
    if (violation.threatLevel >= 8) return 'critical';
    if (violation.threatLevel >= 4) return 'severe';
    if (violation.threatLevel >= 2) return 'moderate';
    return 'low';
  }

  toggleExpand(index: number) {
    const current = new Set(this.expandedItems());
    if (current.has(index)) {
      current.delete(index);
    } else {
      current.add(index);
    }
    this.expandedItems.set(current);
  }

  isExpanded(index: number): boolean {
    return this.expandedItems().has(index);
  }

  toggleAll() {
    if (this.expandedItems().size === this.result.violations.length) {
      this.expandedItems.set(new Set());
    } else {
      const all = new Set<number>();
      this.result.violations.forEach((_, i) => all.add(i));
      this.expandedItems.set(all);
    }
  }

  get allExpanded(): boolean {
    return this.result.violations.length > 0 && this.expandedItems().size === this.result.violations.length;
  }
}
