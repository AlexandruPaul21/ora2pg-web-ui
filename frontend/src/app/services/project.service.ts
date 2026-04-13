import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

// Match the Backend DTO
export interface Project {
  id?: number;
  name: string;

  // Oracle
  oracleHost: string;
  oraclePort: number;
  oracleSid: string;
  oracleUser: string;
  oraclePassword?: string;
  oracleConnectionType: string;
  oracleCustomDsn?: string;

  // Postgres
  postgresHost: string;
  postgresPort: number;
  postgresDb: string;
  postgresUser: string;
  postgresPassword?: string;
  postgresSslMode: string;
  postgresSearchPath?: string;

  tableFilterMode: string;
  selectedTables: string;

  ora2pgConfig: string;
}

@Injectable({
  providedIn: 'root'
})
export class ProjectService {
  private http = inject(HttpClient);
  private apiUrl = 'http://localhost:8080/api/projects'; // Backend URL

  getProjects(): Observable<Project[]> {
    return this.http.get<Project[]>(this.apiUrl);
  }

  createProject(project: Project): Observable<Project> {
    return this.http.post<Project>(this.apiUrl, project);
  }

  deleteProject(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  updateProject(id: number, project: Project): Observable<Project> {
    return this.http.put<Project>(`${this.apiUrl}/${id}`, project);
  }

  testOracle(project: Project): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/test-oracle`, project);
  }

  testPostgres(project: Project): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/test-postgres`, project);
  }

  fetchOracleTables(project: Project): Observable<string[]> {
    return this.http.post<string[]>(`${this.apiUrl}/fetch-oracle-tables`, project);
  }

  getAssessmentReport(id: number): Observable<string> {
    // We must tell Angular to expect raw text, not JSON
    return this.http.get(`${this.apiUrl}/../migration/report/${id}`, { responseType: 'text' });
  }

  getMigrationHistory(projectId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/../migration/history/${projectId}`);
  }

  getMigrationLogs(runId: number): Observable<string> {
    return this.http.get(`${this.apiUrl}/../migration/history/logs/${runId}`, { responseType: 'text' });
  }
}
