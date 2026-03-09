import { Component, OnInit, OnDestroy, NgZone, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ProgressBarModule } from 'primeng/progressbar';

@Component({
  selector: 'app-migration-console',
  standalone: true,
  imports: [CommonModule, ButtonModule, ProgressBarModule],
  templateUrl: './migration-console.html',
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
  `]
})
export class MigrationConsole implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private zone = inject(NgZone);
  private cdr = inject(ChangeDetectorRef); // Manually trigger updates

  projectId: number = 0;
  logs: string[] = [];
  eventSource?: EventSource;
  isRunning = false;
  progress = 0;
  currentStep: string = 'Initializing...';

  ngOnInit() {
    this.projectId = Number(this.route.snapshot.paramMap.get('id'));
  }

  startMigration() {
    this.logs = [];
    this.isRunning = true;
    this.progress = 0;

    // Connect to SSE Endpoint
    this.eventSource = new EventSource(`http://localhost:8080/api/migration/run/${this.projectId}`);

    this.eventSource.addEventListener('log', (event: MessageEvent) => {
      this.zone.run(() => {
        const line = event.data;
        this.logs.push(line);
        this.parseProgress(line);
        this.cdr.detectChanges(); // Force update

        // Auto-scroll to bottom
        setTimeout(() => this.scrollToBottom(), 50);
      });
    });

    this.eventSource.addEventListener('complete', (event: MessageEvent) => {
      this.zone.run(() => {
        this.logs.push(`\n--- ${event.data} ---`);
        this.isRunning = false;
        this.closeConnection();
      });
    });

    this.eventSource.addEventListener('error', (event: MessageEvent) => {
      this.zone.run(() => {
        this.logs.push(`\n--- ERROR: Check connection or logs ---`);
        this.isRunning = false;
        this.closeConnection();
      });
    });
  }

  parseProgress(line: string) {
    // Detect which step we are on and reset the bar if needed
    if (line.includes('>>> STEP 1')) {
      this.currentStep = 'Step 1: Extracting Schema...';
      this.progress = 0;
    }
    if (line.includes('>>> STEP 2')) {
      this.currentStep = 'Step 2: Creating Tables in PostgreSQL...';
      this.progress = 0; // psql doesn't output percentages, so we hold at 0
    }
    if (line.includes('>>> STEP 3')) {
      this.currentStep = 'Step 3: Migrating Data...';
      this.progress = 0;
    }

    // Extract the percentage and update the bar
    const match = line.match(/(\d+\.\d+)%/);
    if (match) {
      let val = parseFloat(match[1]);
      // Clamp the value to 100 to prevent the Oracle stats bug from breaking the UI
      if (val > 100) val = 100;
      this.progress = val;
    }
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
