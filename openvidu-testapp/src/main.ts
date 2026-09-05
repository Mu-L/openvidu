import { provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter, withHashLocation } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';

import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';

bootstrapApplication(AppComponent, {
  providers: [
    provideZoneChangeDetection(),
    provideRouter(routes, withHashLocation()),
    // No animations: Angular Material's overlay animations (select panels, dialogs, menus)
    // open a window during which a click can land on the backdrop instead of the option, so a
    // selection is silently lost.
    {
      provide: MATERIAL_ANIMATIONS,
      useValue: { animationsDisabled: true },
    },
    provideHttpClient(withXhr()),
    {
      provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
      useValue: { appearance: 'outline', subscriptSizing: 'dynamic' },
    },
  ],
}).catch((err) => console.error(err));
