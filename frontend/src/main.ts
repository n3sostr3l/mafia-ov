import { Component } from "@angular/core";
import { bootstrapApplication } from "@angular/platform-browser";
import { provideRouter, RouterOutlet } from "@angular/router";
import { StudioComponent } from "./studio";
@Component({
  selector: "app-root",
  standalone: true,
  imports: [RouterOutlet],
  template: "<router-outlet/>",
})
class AppComponent {}
bootstrapApplication(AppComponent, {
  providers: [
    provideRouter([
      { path: "overlay/:id", component: StudioComponent },
      { path: "games/:id", component: StudioComponent },
      { path: "tournaments/:id", component: StudioComponent },
      { path: "demo", component: StudioComponent },
      { path: "", component: StudioComponent },
      { path: "**", redirectTo: "" },
    ]),
  ],
}).catch(console.error);
