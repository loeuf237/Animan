document.addEventListener('DOMContentLoaded', function() {
    const form = document.getElementById('quicksearch');
    const searchInput = document.getElementById('story');
    const suggestionsDiv = document.getElementById('suggestions');
    let fullSearchActive = false;
    let searchTimeout;
    let currentPage = 1;
    let currentQuery = '';
    let isLoading = false;
    
    function getRecentSearches() {
        const searches = localStorage.getItem('recentMangaSearches');
        return searches ? JSON.parse(searches) : [];
    }
    
    function saveRecentSearch(mangaData) {
        let searches = getRecentSearches();
        searches = searches.filter(s => s.link !== mangaData.link);
        searches.unshift({
            title: mangaData.title,
            poster: mangaData.poster,
            year: mangaData.year,
            link: mangaData.link,
            timestamp: Date.now()
        });
        searches = searches.slice(0, 12);
        localStorage.setItem('recentMangaSearches', JSON.stringify(searches));
    }
    
    function displayRecentSearches() {
        const searches = getRecentSearches();
        
        if (searches.length === 0) {
            hideSearchResults();
            return;
        }
        
        showSearchResults();
        
        let resultsContainer = document.getElementById('search-results-top');
        
        let html = `
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px; color: white; padding: 70px 1px 1px 1px;">
                <h2 style="margin: 0; color: #981000; display: flex; align-items: center; gap: 10px;"><span class="fa fa-history"></span> Recherches récentes</h2>
                <button onclick="closeSearchResults()" style="background: #981000; border: none; color: white; padding: 1px 20px; border-radius: 8px; cursor: pointer; font-size: 20px; font-weight: bold; box-shadow: 0 4px 12px rgba(152, 16, 0, 0.4); transition: all 0.3s ease;" onmouseover="this.style.background='#b81000'; this.style.transform='scale(1.1)';" onmouseout="this.style.background='#981000'; this.style.transform='scale(1)';">×</button>
            </div>
            <div id="search-results-content">
        `;
        
        searches.forEach((search, index) => {
            html += `<div class="search-item recent-item" style="opacity: 0; animation: slideInUp 0.5s ease forwards; animation-delay: ${index * 0.05}s;" onclick="window.location.href='${search.link}'">`;
            html += `<div class="search-poster"><img src="${search.poster}" alt="${search.title}" loading="lazy"></div>`;
            html += `<div class="search-info">`;
            html += `<div class="search-title">${search.title}</div>`;
            if (search.year) {
                html += `<div class="search-year">${search.year}</div>`;
            }
            html += `</div></div>`;
        });
        
        html += `</div>
            <div style="display: flex; justify-content: center; margin-top: 20px; padding: 15px 0; border-top: 1px solid rgba(152, 16, 0, 0.2);">
                <button onclick="closeSearchResults()" style="background: #981000; border: none; color: white; padding: 1px 30px; border-radius: 8px; cursor: pointer; font-size: 18px; font-weight: bold;">× Fermer</button>
            </div>
        `;
        
        resultsContainer.innerHTML = html;
    }
    
    searchInput.addEventListener('focus', function() {
        if (!this.value.trim() && !fullSearchActive) {
            displayRecentSearches();
        }
    });
    
    document.addEventListener('click', function(e) {
        if (!searchInput.contains(e.target) && !document.getElementById('search-results-top')?.contains(e.target)) {
            const resultsContainer = document.getElementById('search-results-top');
            if (resultsContainer && resultsContainer.querySelector('.recent-item')) {
                hideSearchResults();
            }
        }
    });
    
    form.addEventListener('submit', function(e) {
        e.preventDefault();
        const query = searchInput.value.trim();
        if (query.length > 0) {
            fullSearchActive = true;
            performFullSearch(query);
        }
    });
    
    searchInput.addEventListener('keydown', function(e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            const query = searchInput.value.trim();
            if (query.length > 0) {
                fullSearchActive = true;
                performFullSearch(query);
            }
        }
    });
    
    searchInput.addEventListener('input', function() {
        const query = this.value.trim();
        clearTimeout(searchTimeout);
        
        if (fullSearchActive) {
            const resultsContainer = document.getElementById('search-results-top');
            if (resultsContainer && resultsContainer.style.display !== 'none') {
                return;
            }
        }
        
        if (query.length > 0) {
            searchTimeout = setTimeout(() => {
                performLiveSearch(query);
            }, 300);
        } else {
            displayRecentSearches();
        }
    });
    
    function attachClickHandlers() {
        const searchItems = document.querySelectorAll('.search-item:not(.recent-item)');
        searchItems.forEach(item => {
            if (!item.hasAttribute('data-click-attached')) {
                item.setAttribute('data-click-attached', 'true');
                item.addEventListener('click', function(e) {
                    const img = this.querySelector('img');
                    const titleEl = this.querySelector('.search-title');
                    const yearEl = this.querySelector('.search-year');
                    
                    if (img && titleEl) {
                        const onclickAttr = item.getAttribute('onclick');
                        const linkMatch = onclickAttr ? onclickAttr.match(/'([^']+)'/) : null;
                        const mangaData = {
                            title: titleEl.textContent.replace(/\(\d{4}\)/, '').trim(),
                            poster: img.src,
                            year: yearEl ? yearEl.textContent.replace(/[()]/g, '') : '',
                            link: linkMatch ? linkMatch[1] : window.location.href
                        };
                        saveRecentSearch(mangaData);
                    }
                });
            }
        });
    }
    
    function performLiveSearch(query) {
        if (query.length < 2) {
            displayRecentSearches();
            return;
        }
        
        currentQuery = query;
        currentPage = 1;
        showSearchResults();
        
        let resultsContainer = document.getElementById('search-results-top');
        resultsContainer.innerHTML = '<div style="color: white; text-align: center; padding: 20px;"><span class="fa fa-spinner fa-spin" style="font-size: 24px; color: #981000;"></span><br><span style="margin-top: 10px; display: inline-block;">Recherche en cours...</span></div>';
        
        fetch('/engine/ajax/search.php', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: 'query=' + encodeURIComponent(query) + '&page=1'
        })
        .then(response => response.text())
        .then(data => {
            if (searchInput.value.trim() === query) {
                const tempDiv = document.createElement('div');
                tempDiv.innerHTML = data;
                const searchItems = tempDiv.querySelectorAll('.search-item');
                
                searchItems.forEach((item, index) => {
                    item.style.animation = `fadeInUp 0.5s ease forwards ${index * 0.05}s`;
                });
                
                const style = document.createElement('style');
                style.textContent = `
                    @keyframes fadeInUp {
                        from { opacity: 0; transform: translateY(20px); }
                        to { opacity: 1; transform: translateY(0); }
                    }
                    @keyframes slideInUp {
                        from { opacity: 0; transform: translateY(20px); }
                        to { opacity: 1; transform: translateY(0); }
                    }
                `;
                if (!document.getElementById('search-animations')) {
                    style.id = 'search-animations';
                    document.head.appendChild(style);
                }
                
                resultsContainer.innerHTML = `
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px; color: white;">
                        <h2 style="margin: 0; color: #981000;">Résultats</h2>
                        <button onclick="closeSearchResults()" style="background: #981000; border: none; color: white; padding: 1px 20px; border-radius: 8px; cursor: pointer; font-size: 20px; font-weight: bold;">×</button>
                    </div>
                    <div id="search-results-content">${tempDiv.innerHTML}</div>
                    <div style="display: flex; justify-content: center; margin-top: 20px; padding: 15px 0; border-top: 1px solid rgba(152, 16, 0, 0.2);">
                        <button onclick="closeSearchResults()" style="background: #981000; border: none; color: white; padding: 1px 30px; border-radius: 8px; cursor: pointer; font-size: 18px; font-weight: bold;">× Fermer</button>
                    </div>
                `;
                
                attachClickHandlers();
            }
        })
        .catch(error => {
            resultsContainer.innerHTML = '<div style="color: #ff4444; text-align: center; padding: 20px;"><span class="fa fa-exclamation-triangle" style="font-size: 24px;"></span><br>Erreur lors de la recherche</div>';
        });
    }
    
    function showSearchResults() {
        let resultsContainer = document.getElementById('search-results-top');
        if (!resultsContainer) {
            resultsContainer = document.createElement('div');
            resultsContainer.id = 'search-results-top';
            resultsContainer.style.cssText = `
                position: relative;
                z-index: 1000;
                padding: 20px;
                margin: 0;
                background: #1a1a1a;
                border-bottom: 2px solid #981000;
                box-shadow: 0 2px 10px rgba(0,0,0,0.3);
                max-height: 90vh;
                overflow-y: auto;
                overflow-x: hidden;
            `;
            
            const wrapIn = document.querySelector('.wrap-in');
            const headerOuter = document.querySelector('.header-outer');
            
            if (wrapIn) {
                wrapIn.insertBefore(resultsContainer, wrapIn.firstChild);
            } else if (headerOuter) {
                headerOuter.insertAdjacentElement('afterend', resultsContainer);
            } else {
                document.body.insertBefore(resultsContainer, document.body.firstChild);
            }
        }
        
        resultsContainer.style.display = 'block';
        if (window.pageYOffset > 300) {
            resultsContainer.scrollIntoView({ behavior: 'smooth' });
        }
    }
    
    function hideSearchResults() {
        const resultsContainer = document.getElementById('search-results-top');
        if (resultsContainer) {
            resultsContainer.style.display = 'none';
        }
        fullSearchActive = false;
        currentPage = 1;
    }
    
    function performFullSearch(query) {
        fullSearchActive = true;
        currentQuery = query;
        currentPage = 1;
        
        showSearchResults();
        
        let resultsContainer = document.getElementById('search-results-top');
        resultsContainer.innerHTML = '<div style="color: white; text-align: center; padding: 20px;"><span class="fa fa-spinner fa-spin" style="font-size: 24px; color: #981000;"></span><br><span style="margin-top: 10px; display: inline-block;">Recherche en cours...</span></div>';
        
        fetch('/engine/ajax/search.php', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: 'query=' + encodeURIComponent(query) + '&page=1'
        })
        .then(response => response.text())
        .then(data => {
            const tempDiv = document.createElement('div');
            tempDiv.innerHTML = data;
            const searchItems = tempDiv.querySelectorAll('.search-item');
            
            searchItems.forEach((item, index) => {
                item.style.animation = `fadeInUp 0.5s ease forwards ${index * 0.05}s`;
            });
            
            resultsContainer.innerHTML = `
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px; color: white;">
                    <h2 style="margin: 0; color: #981000;">Résultats pour "${query}"</h2>
                    <button onclick="closeSearchResults()" style="background: #981000; border: none; color: white; padding: 1px 20px; border-radius: 8px; cursor: pointer; font-size: 20px; font-weight: bold;">×</button>
                </div>
                <div id="search-results-content">${tempDiv.innerHTML}</div>
                <div style="display: flex; justify-content: center; margin-top: 20px; padding: 15px 0; border-top: 1px solid rgba(152, 16, 0, 0.2);">
                    <button onclick="closeSearchResults()" style="background: #981000; border: none; color: white; padding: 1px 30px; border-radius: 8px; cursor: pointer; font-size: 18px; font-weight: bold;">× Fermer</button>
                </div>
            `;
            
            attachClickHandlers();
            history.pushState({}, '', '?search=' + encodeURIComponent(query));
        })
        .catch(error => {
            resultsContainer.innerHTML = '<div style="color: #ff4444; text-align: center; padding: 20px;"><span class="fa fa-exclamation-triangle" style="font-size: 24px;"></span><br>Erreur lors de la recherche</div>';
        });
    }
    
    window.closeSearchResults = function() {
        hideSearchResults();
    };
});
