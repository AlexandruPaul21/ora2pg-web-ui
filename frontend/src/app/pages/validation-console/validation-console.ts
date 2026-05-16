import { Component, OnInit, OnDestroy, NgZone, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ProgressBarModule } from 'primeng/progressbar';
import { TagModule } from 'primeng/tag';
import { TableModule } from 'primeng/table';
import { FieldsetModule } from 'primeng/fieldset';
import { ProjectService } from '../../services/project.service';

@Component({
  selector: 'app-validation-console',
  standalone: true,
  imports: [CommonModule, ButtonModule, ProgressBarModule, TagModule, TableModule, FieldsetModule, RouterLink],
  templateUrl: './validation-console.html',
  styles: [`
    .terminal {
      background-color: #1e1e1e;
      color: #00ff00;
      font-family: 'Courier New', Courier, monospace;
      padding: 1rem;
      height: 400px;
      overflow-y: auto;
      border-radius: 5px;
      white-space: pre-wrap;
    }
    .blink {
      animation: blinker 1s step-start infinite;
    }
    @keyframes blinker {
      50% { opacity: 0; }
    }
  `]
})
export class ValidationConsole implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private zone = inject(NgZone);
  private cdr = inject(ChangeDetectorRef);
  private projectService = inject(ProjectService);

  projectId = 0;
  logs: string[] = [];
  eventSource?: EventSource;
  isRunning = false;
  progress = 0;
  currentPhase = 'Initializing...';

  report: any = null;
  showReport = false;
  validationRunId: number | null = null;

  ngOnInit() {
    this.projectId = Number(this.route.snapshot.paramMap.get('id'));
    const scope = this.route.snapshot.queryParamMap.get('scope') || '';
    if (scope) {
      this.startValidation(scope);
    }
  }

  startValidation(scope: string) {
    this.logs = [];
    this.isRunning = true;
    this.progress = 0;
    this.report = null;
    this.showReport = false;

    const url = `http://localhost:8080/api/validation/run/${this.projectId}?scope=${encodeURIComponent(scope)}`;
    this.eventSource = new EventSource(url);

    this.eventSource.addEventListener('log', (event: MessageEvent) => {
      this.zone.run(() => {
        const line = event.data;
        this.logs.push(line);
        this.parseProgress(line);
        this.cdr.detectChanges();
        setTimeout(() => this.scrollToBottom(), 50);
      });
    });

    this.eventSource.addEventListener('runId', (event: MessageEvent) => {
      this.zone.run(() => {
        this.validationRunId = Number(event.data);
      });
    });

    this.eventSource.onerror = () => {
      this.zone.run(() => {
        this.isRunning = false;
        this.closeConnection();
        if (this.validationRunId) {
          this.loadReport(this.validationRunId);
        }
      });
    };
  }

  loadReport(runId: number) {
    this.projectService.getValidationReport(runId).subscribe({
      next: (report) => {
        this.report = report;
        this.showReport = true;
        this.cdr.detectChanges();
      },
      error: () => {}
    });
  }

  parseProgress(line: string) {
    if (line.includes('>>> PHASE 1')) {
      this.currentPhase = 'Phase 1: Row Count Validation...';
      this.progress = 0;
    }
    if (line.includes('>>> PHASE 2')) {
      this.currentPhase = 'Phase 2: Schema Validation...';
      this.progress = 33;
    }
    if (line.includes('>>> PHASE 3')) {
      this.currentPhase = 'Phase 3: Data Checksum Validation...';
      this.progress = 66;
    }
    if (line.includes('=== Validation Complete ===')) {
      this.currentPhase = 'Validation Complete';
      this.progress = 100;
    }
  }

  getStatusSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'PASS': return 'success';
      case 'FAIL': return 'danger';
      case 'SKIPPED': return 'secondary';
      default: return 'info';
    }
  }

  getReportStatusSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'SUCCESS': return 'success';
      case 'PARTIAL': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'info';
    }
  }

  getColumnPassCount(columns: any[]): number {
    return columns.filter((c: any) => c.typeCompatible).length;
  }

  getFKPassCount(fks: any[]): number {
    return fks.filter((f: any) => f.status === 'PASS').length;
  }

  getIndexPassCount(indexes: any[]): number {
    return indexes.filter((i: any) => i.status === 'PASS').length;
  }

  getChecksumPassCount(checksums: any[]): number {
    return checksums.filter((c: any) => c.status === 'PASS').length;
  }

  closeConnection() {
    this.eventSource?.close();
  }

  scrollToBottom() {
    const terminal = document.querySelector('.terminal');
    if (terminal) {
      terminal.scrollTop = terminal.scrollHeight;
    }
  }

  ngOnDestroy() {
    this.closeConnection();
  }
}
