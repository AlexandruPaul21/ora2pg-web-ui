import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

// Match the Backend DTO
export interface Project {
  id?: number;
  name: string;
  oracleHost: string;
  oraclePort: number;
  oracleSid: string;
  oracleUser: string;
  oraclePassword?: string;
  postgresHost: string;
  postgresPort: number;
  postgresDb: string;
  postgresUser: string;
  postgresPassword?: string;
  ora2pgConfig?: string;
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

  testOracle(project: Project): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/test-oracle`, project);
  }

  testPostgres(project: Project): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/test-postgres`, project);
  }
}
